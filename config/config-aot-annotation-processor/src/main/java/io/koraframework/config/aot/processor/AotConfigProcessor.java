package io.koraframework.config.aot.processor;

import com.palantir.javapoet.*;
import com.typesafe.config.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
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

    private static final ConfigRenderOptions CONCISE_NO_JSON = ConfigRenderOptions.concise().setJson(false);

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
            baseConfig = baseConfig.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
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
                initCode.add("var rootMap = new $T<String, $T<?>>();\n", LinkedHashMap.class, configValue);
                generateFieldResolvers(initCode, config, path, baseConfig);
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

    private void generateFieldResolvers(com.palantir.javapoet.CodeBlock.Builder initCode, javax.lang.model.element.TypeElement configElement, String currentPath, com.typesafe.config.Config currentConfig) {
        var configFields = ConfigUtils.parseFields(types, configElement).left();
        if (configFields == null) return;

        for (var field : configFields) {
            var fullKey = currentPath.isBlank() ? field.name() : currentPath + "." + field.name();

            var hasInterfaceDefault = field.hasDefault();
            var typeName = field.typeName();

            if (typeName instanceof com.palantir.javapoet.ClassName className && !isStandardType(className.toString())) {
                var targetElement = processingEnv.getElementUtils().getTypeElement(className.toString());
                if (targetElement != null && targetElement.getKind() == javax.lang.model.element.ElementKind.INTERFACE) {
                    generateFieldResolvers(initCode, targetElement, fullKey, currentConfig);
                    continue;
                }
            }

            var baseVal = getConfigValue(currentConfig, fullKey);
            var baseValBlock = baseVal != null ? CodeBlock.of("$S", baseVal.toString()) : CodeBlock.of("null");

            initCode.add("resolveAndPut(rootMap, $S, $S, $L, $L);\n",
                currentPath, field.name(), baseValBlock, hasInterfaceDefault
            );
        }
    }

    private boolean isStandardType(String className) {
        return className.startsWith("java.lang.")
            || className.startsWith("java.time.")
            || className.startsWith("java.util.");
    }

    private Object getConfigValue(Config config, String path) {
        if (config == null || path.isBlank()) {
            return null;
        }

        String[] parts = path.split("\\.");
        ConfigObject currentObject = config.root();
        ConfigValue targetValue = null;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];
            targetValue = currentObject.get(key);

            if (targetValue == null) {
                List<String> relaxedNames = PathElement.get(key).relaxedNames();
                for (String variant : relaxedNames) {
                    targetValue = currentObject.get(variant);
                    if (targetValue != null) {
                        break;
                    }
                }
            }

            if (targetValue == null) {
                return null;
            }

            if (i < parts.length - 1) {
                try {
                    if (targetValue.valueType() == ConfigValueType.OBJECT) {
                        currentObject = (ConfigObject) targetValue;
                    } else {
                        return null;
                    }
                } catch (ConfigException.NotResolved notResolved) {
                    return targetValue.render(CONCISE_NO_JSON);
                }
            }
        }

        try {
            if (targetValue.valueType() == ConfigValueType.NULL) {
                return null;
            }

            if (targetValue.valueType() == ConfigValueType.OBJECT) {
                return null;
            }

            if (targetValue.valueType() == ConfigValueType.LIST) {
                return targetValue.render(ConfigRenderOptions.concise());
            }

            return targetValue.unwrapped();
        } catch (ConfigException.NotResolved notResolved) {
            return targetValue.render(CONCISE_NO_JSON);
        }
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
