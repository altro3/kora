package io.koraframework.config.aot.processor;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class AotConfigProcessor extends AbstractKoraProcessor {
    private final Map<String, Object> compileTimeConfig = new HashMap<>();
    private boolean isConfigLoaded = false;
    private final Set<ClassName> generatedModules = new LinkedHashSet<>();

    @Override
    public Set<ClassName> getSupportedAnnotationClassNames() {
        return Set.of(ConfigClassNames.configSourceAnnotation);
    }

    private void loadConfigAtCompileTime() {
        if (isConfigLoaded) {
            return;
        }
        isConfigLoaded = true;

        var hoconContent = readResource("application.conf");
        if (!hoconContent.isBlank()) {
            var profile = getActiveProfile();
            var profileContent = profile.isBlank() ? "" : readResource("application-" + profile + ".conf");
            compileTimeConfig.putAll(HoconAotParser.parse(hoconContent, profileContent));
            return;
        }

        var yamlContent = readResource("application.yaml");
        if (yamlContent.isBlank()) {
            yamlContent = readResource("application.yml");
        }
        if (!yamlContent.isBlank()) {
            var profile = getActiveProfile();
            var profileContent = profile.isBlank() ? "" : readResource("application-" + profile + ".yaml");
            if (profileContent.isBlank() && !profile.isBlank()) {
                profileContent = readResource("application-" + profile + ".yml");
            }
            compileTimeConfig.putAll(YamlAotParser.parse(yamlContent, profileContent));
        }
    }

    private String getActiveProfile() {
        var profile = System.getProperty("kora.profiles", System.getenv("KORA_PROFILES"));
        return profile != null ? profile.trim() : "";
    }

    private String readResource(String name) {
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
            var searchPath = runDir.resolve("build/in-test-generated");
            if (Files.exists(searchPath)) {
                try (var stream = Files.walk(searchPath)) {
                    var foundFile = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(name))
                        .findFirst();

                    if (foundFile.isPresent()) {
                        return Files.readString(foundFile.get(), StandardCharsets.UTF_8);
                    }
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

        loadConfigAtCompileTime();

        for (var annotated : annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of())) {
            var config = (TypeElement) annotated.element();
            var moduleName = config.getSimpleName() + "AotModule";
            var packageElement = elements.getPackageOf(config);
            var packageName = packageElement.getQualifiedName().toString();
            var mapperSimpleName = "$" + config.getSimpleName().toString() + "_ConfigValueMapper";
            var implClassName = ClassName.get(packageName, mapperSimpleName, config.getSimpleName().toString() + "_Impl");
            var generatedModuleClassName = ClassName.get(packageName, moduleName);

            generatedModules.add(generatedModuleClassName);

            var typeBuilder = TypeSpec.interfaceBuilder(moduleName)
                .addOriginatingElement(config)
                .addAnnotation(AnnotationUtils.generated(AotConfigProcessor.class))
                .addAnnotation(CommonClassNames.module)
                .addModifiers(Modifier.PUBLIC);

            var path = AnnotationUtils.<String>parseAnnotationValueWithoutDefault(
                AnnotationUtils.findAnnotation(config, ConfigClassNames.configSourceAnnotation),
                "value"
            );
            if (path == null) {
                path = "";
            }

            var configNameStr = config.getSimpleName().toString();
            var methodName = Character.toLowerCase(configNameStr.charAt(0)) + configNameStr.substring(1);
            var method = MethodSpec.methodBuilder(methodName)
                .returns(TypeName.get(config.asType()))
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);

            var configFields = ConfigUtils.parseFields(types, config).left();
            if (configFields != null && !configFields.isEmpty()) {
                var initCode = CodeBlock.builder();
                initCode.add("return new $T(\n", implClassName);

                for (var i = 0; i < configFields.size(); i++) {
                    var field = configFields.get(i);
                    var fullKey = path.isBlank() ? field.name() : path + "." + field.name();
                    var envKey = fullKey.toUpperCase().replace('.', '_').replace('-', '_');

                    // Железно достаём значение из компиляционной мапы
                    var rawValue = getConfigValue(compileTimeConfig, fullKey);
                    var defaultsAccessor = CodeBlock.of("AotDefaults.INSTANCE.$L()", field.name());

                    initCode.add("  ");
                    if (field.typeName().equals(TypeName.INT) || field.typeName().equals(TypeName.INT.box())) {
                        initCode.add("resolveInt($S, $S, $L, $L)", envKey, fullKey, rawValue != null ? rawValue : "null", defaultsAccessor);
                    } else if (field.typeName().equals(TypeName.BOOLEAN) || field.typeName().equals(TypeName.BOOLEAN.box())) {
                        initCode.add("resolveBoolean($S, $S, $L, $L)", envKey, fullKey, rawValue != null ? rawValue : "null", defaultsAccessor);
                    } else if (field.typeName().equals(ClassName.get(String.class))) {
                        // Строковые литералы обязательно гоним через $S, чтобы JavaPoet ставил кавычки "localhost"
                        initCode.add("resolveString($S, $S, $S, $L)", envKey, fullKey, rawValue != null ? rawValue.toString() : null, defaultsAccessor);
                    } else {
                        initCode.add("resolveObject($S, $S, $L, $L)", envKey, fullKey, rawValue != null ? rawValue : "null", defaultsAccessor);
                    }

                    if (i < configFields.size() - 1) {
                        initCode.add(",\n");
                    }
                }
                initCode.add("\n);\n");
                method.addCode(initCode.build());
            }

            typeBuilder.addMethod(method.build());
            addResolveHelpers(typeBuilder);
            typeBuilder.addMethod(createResolveObjectMethod());

            var defaultsBuilder = TypeSpec.classBuilder("AotDefaults")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(ClassName.get(config));

            for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                if (methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }

                var returnType = TypeName.get(methodElement.getReturnType());
                var mSpec = MethodSpec.methodBuilder(methodElement.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(returnType);

                if (returnType.equals(TypeName.INT) || returnType.equals(TypeName.INT.box())) {
                    mSpec.addStatement("return 0");
                } else if (returnType.equals(TypeName.BOOLEAN) || returnType.equals(TypeName.BOOLEAN.box())) {
                    mSpec.addStatement("return false");
                } else {
                    mSpec.addStatement("return null");
                }
                defaultsBuilder.addMethod(mSpec.build());
            }

            defaultsBuilder.addField(FieldSpec.builder(ClassName.get(config), "INSTANCE")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new AotDefaults()")
                .build());

            typeBuilder.addType(defaultsBuilder.build());

            var javaFile = JavaFile.builder(packageName, typeBuilder.build()).build();
            CommonUtils.safeWriteTo(processingEnv, javaFile);
        }
    }

    private void generateGlobalAotConfigModule() {
        if (generatedModules.isEmpty()) {
            return;
        }

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

    private void addResolveHelpers(TypeSpec.Builder typeBuilder) {
        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(String.class, "fileValue")
            .addParameter(String.class, "fallback")
            .addStatement("var value = resolveRaw(envKey, sysKey, fileValue)")
            .addStatement("return value != null ? value.toString() : fallback")
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveInt")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(int.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object.class, "fileValue")
            .addParameter(int.class, "fallback")
            .addStatement("var value = resolveRaw(envKey, sysKey, fileValue)")
            .addStatement("if (value == null) return fallback")
            .addStatement("if (value instanceof Number n) return n.intValue()")
            .addStatement("return Integer.parseInt(value.toString())")
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveBoolean")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object.class, "fileValue")
            .addParameter(boolean.class, "fallback")
            .addStatement("var value = resolveRaw(envKey, sysKey, fileValue)")
            .addStatement("if (value == null) return fallback")
            .addStatement("if (value instanceof Boolean b) return b")
            .addStatement("return Boolean.parseBoolean(value.toString())")
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveObject")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object.class, "fileValue")
            .addParameter(Object.class, "fallback")
            .addStatement("var value = resolveRaw(envKey, sysKey, fileValue)")
            .addStatement("return value != null ? value : fallback")
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveRaw")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object.class, "fileValue")
            .addCode("""
                        var env = System.getenv(envKey);
                        if (env != null) return env;
                        var sys = System.getProperty(sysKey);
                        if (sys != null) return sys;
                        return fileValue;
                        """)
            .build());
    }

    private Object getConfigValue(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        var parts = path.split("\\.");
        Object current = map;
        for (var part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                current = currentMap.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private MethodSpec createResolveObjectMethod() {
        return MethodSpec.methodBuilder("resolveObject")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object.class, "fallback")
            .addCode("""
                var env = System.getenv(envKey);
                if (env != null) return env;
                var sys = System.getProperty(sysKey);
                if (sys != null) return sys;
                return fallback;
                """)
            .build();
    }
}
