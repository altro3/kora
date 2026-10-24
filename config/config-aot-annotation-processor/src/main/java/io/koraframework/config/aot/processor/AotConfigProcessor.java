package io.koraframework.config.aot.processor;

import com.palantir.javapoet.*;
import com.typesafe.config.*;
import com.typesafe.config.Config;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.*;
import io.koraframework.config.common.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class AotConfigProcessor extends AbstractKoraProcessor {
    private Config baseConfig = null;
    private boolean isConfigLoaded = false;
    private final Set<ClassName> generatedModules = new LinkedHashSet<>();

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("aot.config.force.reload");
    }

    @Override
    public Set<ClassName> getSupportedAnnotationClassNames() {
        return Set.of(ConfigClassNames.configSourceAnnotation);
    }

    private void loadAllConfigsAtCompileTime(String packageName) {
        if (isConfigLoaded) return;
        isConfigLoaded = true;

        var hoconBase = readResource("application.conf", packageName);
        var yamlBase = readResource("application.yaml", packageName);
        if (yamlBase.isBlank()) {
            yamlBase = readResource("application.yml", packageName);
        }

        if (!hoconBase.isBlank()) {
            baseConfig = ConfigFactory.parseString(hoconBase);
        } else if (!yamlBase.isBlank()) {
            var parsedMap = YamlAotParser.parse(yamlBase, null);
            if (parsedMap != null) {
                baseConfig = ConfigFactory.parseMap(parsedMap);
            }
        }

        if (baseConfig != null) {
            baseConfig = baseConfig.resolve();
        }
    }

    private String readResource(String name, String packageName) {
        for (var location : List.of(StandardLocation.CLASS_OUTPUT, StandardLocation.SOURCE_PATH)) {
            try {
                var resource = processingEnv.getFiler().getResource(location, "", name);
                try (var is = resource.openInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {}
        }

        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            String packagePath = packageName.replace('.', '/');

            var searchPaths = List.of(
                runDir.resolve("build/in-test-generated/classes").resolve(packagePath),
                runDir.resolve("build/in-test-generated/sources").resolve(packagePath),
                runDir.resolve("src/main/resources")
            );

            for (var path : searchPaths) {
                var file = path.resolve(name);
                if (Files.exists(file)) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ignored) {}

        return "";
    }

    @Override
    protected void process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv, Map<ClassName, List<AnnotatedElement>> annotatedElements) {
        if (roundEnv.processingOver()) {
            generateGlobalAotConfigModule();
            return;
        }

        var resolversClass = ClassName.get("io.koraframework.config.common.aot", "AotConfigResolvers");
        var defaultComponentAnno = ClassName.get("io.koraframework.common.annotation", "DefaultComponent");

        var targetElements = annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of());
        if (targetElements.isEmpty()) {
            return;
        }

        if (!isConfigLoaded) {
            var firstConfig = (TypeElement) targetElements.get(0).element();
            var firstPackageElement = elements.getPackageOf(firstConfig);
            var firstPackageName = firstPackageElement.getQualifiedName().toString();
            loadAllConfigsAtCompileTime(firstPackageName);
        }

        for (var annotated : targetElements) {
            var config = (TypeElement) annotated.element();
            var moduleName = config.getSimpleName() + "AotModule";
            var packageElement = elements.getPackageOf(config);
            var packageName = packageElement.getQualifiedName().toString();

            generatedModules.add(ClassName.get(packageName, moduleName));

            var typeBuilder = TypeSpec.interfaceBuilder(moduleName)
                .addOriginatingElement(config)
                .addAnnotation(AnnotationUtils.generated(AotConfigProcessor.class))
                .addAnnotation(CommonClassNames.module)
                .addModifiers(Modifier.PUBLIC);

            var path = AnnotationUtils.<String>parseAnnotationValueWithoutDefault(
                AnnotationUtils.findAnnotation(config, ConfigClassNames.configSourceAnnotation), "value"
            );
            if (path == null) path = "";

            var configNameStr = config.getSimpleName().toString();
            var methodName = Character.toLowerCase(configNameStr.charAt(0)) + configNameStr.substring(1);
            var method = MethodSpec.methodBuilder(methodName)
                .returns(TypeName.get(config.asType()))
                .addAnnotation(defaultComponentAnno)
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);

            var configFields = ConfigUtils.parseFields(types, config).left();
            if (configFields != null && !configFields.isEmpty()) {
                var initCode = CodeBlock.builder();

                var mapperInterface = ClassName.get("io.koraframework.config.common.mapper", "ConfigValueMapper");
                var simpleOrigin = ClassName.get("io.koraframework.config.common.impl", "SimpleConfigValueOrigin");
                var configOrigin = ClassName.get("io.koraframework.config.common.origin", "SimpleConfigOrigin");
                var valuePath = ClassName.get("io.koraframework.config.common", "ConfigValuePath");
                var objectValue = ClassName.get("io.koraframework.config.common.ConfigValue", "ObjectValue");
                var configValue = ClassName.get("io.koraframework.config.common", "ConfigValue");
                var pathElement = ClassName.get("io.koraframework.config.common", "PathElement");

                var configMapperType = ParameterizedTypeName.get(mapperInterface, TypeName.get(config.asType()));
                method.addParameter(configMapperType, "mapper");

                initCode.add("var origin = new $T(\n", simpleOrigin);
                initCode.add("  new $T(\"aot-compiled\"),\n", configOrigin);
                initCode.add("  $T.root()\n", valuePath);
                initCode.add(");\n\n");

                initCode.add("var rootMap = new $T<String, $T<?>>();\n", java.util.LinkedHashMap.class, configValue);

                for (var field : configFields) {
                    var fullKey = path.isBlank() ? field.name() : path + "." + field.name();
                    var baseVal = getConfigValue(baseConfig, fullKey);

                    var hasInterfaceDefault = false;
                    for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                        if (methodElement.getSimpleName().toString().equals(field.name()) && methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                            hasInterfaceDefault = true;
                            break;
                        }
                    }

                    var baseValBlock = baseVal != null ? CodeBlock.of("$S", baseVal.toString()) : CodeBlock.of("null");

                    initCode.add("resolveAndPut(rootMap, $S, $S, $L, $L);\n",
                        path, field.name(), baseValBlock, hasInterfaceDefault
                    );
                }

                initCode.add("\nvar finalObjectValue = new $T(origin, rootMap);\n", objectValue);

                if (path.isBlank()) {
                    initCode.add("var targetValue = finalObjectValue;\n");
                } else {
                    initCode.add("var targetValue = finalObjectValue.get($T.get($S));\n", pathElement, path);
                }

                initCode.add("return mapper.map(targetValue);\n");

                method.addCode(initCode.build());
            }

            typeBuilder.addMethod(method.build());

            var javaFile = JavaFile.builder(packageName, typeBuilder.build())
                .addStaticImport(resolversClass, "*")
                .build();

            CommonUtils.safeWriteTo(processingEnv, javaFile);
        }
    }

    private Object getConfigValue(Config config, String path) {
        if (config == null || path.isBlank()) {
            return null;
        }

        if (!config.hasPath(path)) {
            var key = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            var prefix = path.contains(".") ? path.substring(0, path.lastIndexOf('.') + 1) : "";
            var relaxedNames = PathElement.get(key).relaxedNames();

            for (var variant : relaxedNames) {
                var alternativePath = prefix + variant;
                if (config.hasPath(alternativePath)) {
                    path = alternativePath;
                    break;
                }
            }
        }

        if (!config.hasPath(path)) {
            return null;
        }

        var configValue = config.getValue(path);
        if (configValue.valueType() == ConfigValueType.NULL) {
            return null;
        }

        return configValue.unwrapped();
    }

    private void generateGlobalAotConfigModule() {
        if (generatedModules.isEmpty()) return;
        var targetPackage = generatedModules.iterator().next().packageName();
        var globalModuleName = "AotConfigModule";
        var globalModuleBuilder = TypeSpec.interfaceBuilder(globalModuleName)
            .addAnnotation(AnnotationUtils.generated(AotConfigProcessor.class))
            .addAnnotation(CommonClassNames.module)
            .addModifiers(Modifier.PUBLIC);

        for (var moduleClassName : generatedModules) {
            globalModuleBuilder.addSuperinterface(moduleClassName);
        }

        var javaFile = JavaFile.builder(targetPackage, globalModuleBuilder.build()).build();
        CommonUtils.safeWriteTo(processingEnv, javaFile);
    }
}
