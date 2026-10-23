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
import java.util.regex.*;

public class AotConfigProcessor extends AbstractKoraProcessor {
    private final Map<String, Object> baseConfig = new HashMap<>();
    private final Map<String, Map<String, Object>> profileConfigs = new HashMap<>();
    private boolean isConfigLoaded = false;
    private final Set<ClassName> generatedModules = new LinkedHashSet<>();

    @Override
    public Set<ClassName> getSupportedAnnotationClassNames() {
        return Set.of(ConfigClassNames.configSourceAnnotation);
    }

    private void loadAllConfigsAtCompileTime() {
        if (isConfigLoaded) return;
        isConfigLoaded = true;

        var hoconBase = readResource("application.conf");
        var yamlBase = readResource("application.yaml");
        if (yamlBase.isBlank()) {
            yamlBase = readResource("application.yml");
        }

        if (!hoconBase.isBlank()) {
            var parsed = HoconAotParser.parse(hoconBase, null);
            if (parsed != null) baseConfig.putAll(parsed);
        } else if (!yamlBase.isBlank()) {
            var parsed = YamlAotParser.parse(yamlBase, null);
            if (parsed != null) baseConfig.putAll(parsed);
        }

        var discoveredProfiles = discoverProfileFiles();
        for (var entry : discoveredProfiles.entrySet()) {
            var profileName = entry.getKey();
            var files = entry.getValue();
            var pConfig = new HashMap<String, Object>();

            for (var file : files) {
                Map<String, Object> parsed;
                if (file.filename().endsWith(".conf")) {
                    parsed = HoconAotParser.parse(hoconBase.isBlank() ? null : hoconBase, file.content());
                } else {
                    parsed = YamlAotParser.parse(yamlBase.isBlank() ? null : yamlBase, file.content());
                }
                if (parsed != null) pConfig.putAll(parsed);
            }
            profileConfigs.put(profileName, pConfig);
        }
    }

    private Map<String, List<ProfileFile>> discoverProfileFiles() {
        var result = new HashMap<String, List<ProfileFile>>();
        var pattern = Pattern.compile("application-(.+)\\.(conf|yaml|yml)");
        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            var searchPaths = List.of(
                runDir.resolve("src/main/resources"),
                runDir.resolve("build/resources/main")
            );
            for (var searchPath : searchPaths) {
                if (Files.exists(searchPath)) {
                    try (var stream = Files.walk(searchPath)) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            var filename = p.getFileName().toString();
                            var matcher = pattern.matcher(filename);
                            if (matcher.matches()) {
                                var profile = matcher.group(1);
                                try {
                                    var content = Files.readString(p, StandardCharsets.UTF_8);
                                    result.computeIfAbsent(profile, _ -> new ArrayList<>())
                                        .add(new ProfileFile(filename, content));
                                } catch (IOException ignored) {}
                            }
                        });
                    }
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    private String readResource(String name) {
        for (var location : List.of(StandardLocation.CLASS_OUTPUT, StandardLocation.SOURCE_PATH)) {
            try {
                var resource = processingEnv.getFiler().getResource(location, "", name);
                try (var is = resource.openInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }
        }

        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            var searchPaths = List.of(
                runDir.resolve("build/in-test-generated"),
                runDir.resolve("build/in-test-generated/classes"),
                runDir.resolve("build/resources/main")
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

        loadAllConfigsAtCompileTime();

        for (var annotated : annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of())) {
            var config = (TypeElement) annotated.element();
            var moduleName = config.getSimpleName() + "AotModule";
            var packageElement = elements.getPackageOf(config);
            var packageName = packageElement.getQualifiedName().toString();
            var mapperSimpleName = "$" + config.getSimpleName() + "_ConfigValueMapper";
            var implClassName = ClassName.get(packageName, mapperSimpleName, config.getSimpleName() + "_Impl");

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
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);

            var configFields = ConfigUtils.parseFields(types, config).left();
            if (configFields != null && !configFields.isEmpty()) {
                var initCode = CodeBlock.builder();
                initCode.add("return new $T(\n", implClassName);

                for (var i = 0; i < configFields.size(); i++) {
                    var field = configFields.get(i);
                    var fullKey = path.isBlank() ? field.name() : path + "." + field.name();
                    var envKey = fullKey.toUpperCase().replace('.', '_').replace('-', '_');

                    var isFirst = true;
                    var hasProfiles = false;
                    var profileValuesBuilder = CodeBlock.builder().add("new Object[] {");

                    for (var entry : profileConfigs.entrySet()) {
                        var profileVal = getConfigValue(entry.getValue(), fullKey);
                        if (profileVal != null) {
                            hasProfiles = true;
                            if (!isFirst) profileValuesBuilder.add(", ");
                            isFirst = false;
                            profileValuesBuilder.add("$S, $S", entry.getKey(), profileVal.toString());
                        }
                    }
                    profileValuesBuilder.add("}");
                    var profileValuesCode = hasProfiles ? profileValuesBuilder.build() : CodeBlock.of("null");

                    var baseVal = getConfigValue(baseConfig, fullKey);
                    var baseValBuilder = CodeBlock.builder();
                    appendLiteral(baseValBuilder, baseVal);
                    var baseValCode = baseValBuilder.build();

                    var hasDefault = false;
                    for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                        if (methodElement.getSimpleName().toString().equals(field.name()) && methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                            hasDefault = true;
                            break;
                        }
                    }

                    initCode.add("  ");
                    if (field.typeName().equals(TypeName.INT) || field.typeName().equals(TypeName.INT.box())) {
                        initCode.add("resolveInt($S, $S, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault);
                    } else if (field.typeName().equals(TypeName.BOOLEAN) || field.typeName().equals(TypeName.BOOLEAN.box())) {
                        initCode.add("resolveBoolean($S, $S, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault);
                    } else if (field.typeName().equals(ClassName.get(String.class))) {
                        initCode.add("resolveString($S, $S, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault);
                    } else {
                        initCode.add("resolveObject($S, $S, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault);
                    }

                    if (i < configFields.size() - 1) initCode.add(",\n");
                }
                initCode.add("\n);\n");
                method.addCode(initCode.build());
            }

            typeBuilder.addMethod(method.build());
            addResolveHelpers(typeBuilder);

            var javaFile = JavaFile.builder(packageName, typeBuilder.build()).build();
            CommonUtils.safeWriteTo(processingEnv, javaFile);
        }
    }

    private void appendLiteral(CodeBlock.Builder builder, Object value) {
        if (value == null) {
            builder.add("null");
            return;
        }
        switch (value) {
            case String s -> builder.add("$S", s);
            case List<?> list -> appendList(builder, list);
            case Map<?, ?> map -> appendMap(builder, (Map<String, Object>) map);
            default -> builder.add("$L", value);
        }
    }

    private void appendList(CodeBlock.Builder builder, List<?> list) {
        if (list.isEmpty()) {
            builder.add("$T.of()", List.class);
            return;
        }
        builder.add("$T.of(", List.class);
        var iterator = list.iterator();
        while (iterator.hasNext()) {
            appendLiteral(builder, iterator.next());
            if (iterator.hasNext()) {
                builder.add(", ");
            }
        }
        builder.add(")");
    }

    private void appendMap(CodeBlock.Builder builder, Map<String, Object> map) {
        if (map.isEmpty()) {
            builder.add("$T.of()", Map.class);
            return;
        }
        builder.add("$T.of(", Map.class);
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            builder.add("$S, ", entry.getKey());
            appendLiteral(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.add(", ");
            }
        }
        builder.add(")");
    }

    private void addResolveHelpers(TypeSpec.Builder typeBuilder) {
        var profileHolder = TypeSpec.classBuilder("AotProfileHolder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addField(FieldSpec.builder(String[].class, "ACTIVE_PROFILES")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("""
                    System.getProperty("kora.profiles") != null
                    ? System.getProperty("kora.profiles").split(",")
                    : (System.getenv("KORA_PROFILES") != null ? System.getenv("KORA_PROFILES").split(",") : new String[0])
                    """)
                .build())
            .build();

        typeBuilder.addType(profileHolder);

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveRaw")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object[].class, "profileValues")
            .addParameter(Object.class, "baseValue")
            .addCode("""
                var env = System.getenv(envKey);
                if (env != null) return env;
                var sys = System.getProperty(sysKey);
                if (sys != null) return sys;
                
                if (profileValues != null) {
                    var actives = AotProfileHolder.ACTIVE_PROFILES;
                    for (var active : actives) {
                        var trimmed = active.trim();
                        for (int i = 0; i < profileValues.length; i += 2) {
                            if (trimmed.equals(profileValues[i])) return profileValues[i+1];
                        }
                    }
                }
                return baseValue;
                """)
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object[].class, "profileValues")
            .addParameter(Object.class, "baseValue")
            .addParameter(boolean.class, "hasDefault")
            .addStatement("var value = resolveRaw(envKey, sysKey, profileValues, baseValue)")
            .addStatement("if (value != null) return value.toString()")
            .addCode("""
                if (hasDefault) return null;
                throw new java.util.NoSuchElementException("Required configuration property is missing: " + sysKey);
                """)
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveInt")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(int.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object[].class, "profileValues")
            .addParameter(Object.class, "baseValue")
            .addParameter(boolean.class, "hasDefault")
            .addStatement("var value = resolveRaw(envKey, sysKey, profileValues, baseValue)")
            .addStatement("if (value instanceof Number n) return n.intValue()")
            .addStatement("if (value != null) return Integer.parseInt(value.toString())")
            .addCode("""
                if (hasDefault) return 0;
                throw new java.util.NoSuchElementException("Required configuration property is missing: " + sysKey);
                """)
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveBoolean")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object[].class, "profileValues")
            .addParameter(Object.class, "baseValue")
            .addParameter(boolean.class, "hasDefault")
            .addStatement("var value = resolveRaw(envKey, sysKey, profileValues, baseValue)")
            .addStatement("if (value instanceof Boolean b) return b")
            .addStatement("if (value != null) return Boolean.parseBoolean(value.toString())")
            .addCode("""
                if (hasDefault) return false;
                throw new java.util.NoSuchElementException("Required configuration property is missing: " + sysKey);
                """)
            .build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("resolveObject")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "envKey")
            .addParameter(String.class, "sysKey")
            .addParameter(Object[].class, "profileValues")
            .addParameter(Object.class, "baseValue")
            .addParameter(boolean.class, "hasDefault")
            .addStatement("var value = resolveRaw(envKey, sysKey, profileValues, baseValue)")
            .addStatement("if (value != null) return value")
            .addCode("""
                if (hasDefault) return null;
                throw new java.util.NoSuchElementException("Required configuration property is missing: " + sysKey);
                """)
            .build());
    }

    private Object getConfigValue(Map<String, Object> map, String path) {
        if (map == null || path.isBlank()) return null;
        if (map.containsKey(path)) return map.get(path);

        var parts = path.split("\\.");
        var current = (Object) map;

        for (var part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                current = currentMap.get(part);
            } else {
                current = null;
                break;
            }
        }
        if (current != null) return current;
        return map.get(path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path);
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

    private record ProfileFile(String filename, String content) {}
}
