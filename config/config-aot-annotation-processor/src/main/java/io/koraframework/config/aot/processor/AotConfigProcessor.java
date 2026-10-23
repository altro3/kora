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
            } catch (IOException ignored) {}
        }

        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            var searchPaths = List.of(
                runDir.resolve("src/main/resources"),
                runDir.resolve("build/resources/main"),
                runDir.resolve("build/in-test-generated"),
                runDir.resolve("build/in-test-generated/classes"),
                runDir.resolve("build/in-test-generated/sources"),
                Path.of("build/in-test-generated/classes"),
                Path.of("build/in-test-generated/sources")
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

        var resolversClass = ClassName.get("io.koraframework.config.common.aot", "AotConfigResolvers");
        var defaultComponentAnno = ClassName.get("io.koraframework.common.annotation", "DefaultComponent");

        for (var annotated : annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of())) {
            var config = (TypeElement) annotated.element();
            var moduleName = config.getSimpleName() + "AotModule";
            var packageElement = elements.getPackageOf(config);
            var packageName = packageElement.getQualifiedName().toString();
            var mapperSimpleName = "$" + config.getSimpleName() + "_ConfigValueMapper";
            var implClassName = ClassName.get(packageName, mapperSimpleName, config.getSimpleName() + "_Impl");
            var mapperClassName = ClassName.get(packageName, mapperSimpleName);

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

            // 1. Проверяем НА УРОВНЕ КОМПИЛЯЦИИ, есть ли вообще дефолты в этом интерфейсе
            var interfaceHasAnyDefaults = false;
            for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                if (methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                    interfaceHasAnyDefaults = true;
                    break;
                }
            }

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
                            profileValuesBuilder.add("$S, ", entry.getKey());
                            appendLiteral(profileValuesBuilder, profileVal);
                        }
                    }
                    profileValuesBuilder.add("}");
                    var profileValuesCode = hasProfiles ? profileValuesBuilder.build() : CodeBlock.of("null");

                    var baseVal = getConfigValue(baseConfig, fullKey);
                    var baseValBuilder = CodeBlock.builder();
                    appendLiteral(baseValBuilder, baseVal);
                    var baseValCode = baseValBuilder.build();

                    // Проверяем, имеет ли конкретно это поле дефолтное значение
                    var hasDefault = false;
                    for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                        if (methodElement.getSimpleName().toString().equals(field.name()) && methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                            hasDefault = true;
                            break;
                        }
                    }

                    initCode.add("  ");
                    boolean needsFullParams = hasProfiles || baseVal != null;

                    // 2. Строим выражение для дефолта: если дефолтов в интерфейсе вообще нет, сразу пишем литерал
                    String defExpr;
                    if (field.typeName().equals(TypeName.INT) || field.typeName().equals(TypeName.INT.box())) {
                        defExpr = interfaceHasAnyDefaults ? CodeBlock.of("$T.DEFAULTS.$L()", mapperClassName, field.name()).toString() : "0";
                        if (needsFullParams) {
                            initCode.add("resolveInt($S, $S, $L, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault, defExpr);
                        } else {
                            initCode.add("resolveInt($S, $S, $L, $L)", envKey, fullKey, hasDefault, defExpr);
                        }
                    } else if (field.typeName().equals(TypeName.BOOLEAN) || field.typeName().equals(TypeName.BOOLEAN.box())) {
                        defExpr = interfaceHasAnyDefaults ? CodeBlock.of("$T.DEFAULTS.$L()", mapperClassName, field.name()).toString() : "false";
                        if (needsFullParams) {
                            initCode.add("resolveBoolean($S, $S, $L, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, hasDefault, defExpr);
                        } else {
                            initCode.add("resolveBoolean($S, $S, $L, $L)", envKey, fullKey, hasDefault, defExpr);
                        }
                    } else if (field.typeName().equals(ClassName.get(String.class))) {
                        defExpr = interfaceHasAnyDefaults ? CodeBlock.of("$T.DEFAULTS.$L()", mapperClassName, field.name()).toString() : "null";
                        if (needsFullParams) {
                            initCode.add("resolveString($S, $S, $L, $L, $L)", envKey, fullKey, profileValuesCode, baseValCode, defExpr);
                        } else {
                            initCode.add("resolveString($S, $S, $L)", envKey, fullKey, defExpr);
                        }
                    } else {
                        if (needsFullParams) {
                            initCode.add("($T) resolveObject($S, $S, $L, $L, $L)", field.typeName(), envKey, fullKey, profileValuesCode, baseValCode, hasDefault);
                        } else {
                            initCode.add("($T) resolveObject($S, $S, $L)", field.typeName(), envKey, fullKey, hasDefault);
                        }
                    }

                    if (i < configFields.size() - 1) initCode.add(",\n");
                }
                initCode.add("\n);\n");
                method.addCode(initCode.build());
            }

            typeBuilder.addMethod(method.build());

            var javaFile = JavaFile.builder(packageName, typeBuilder.build())
                .addStaticImport(resolversClass, "*")
                .build();

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
            case Number n -> builder.add("$L", n);
            case Boolean b -> builder.add("$L", b);
            case List<?> list -> appendList(builder, list);
            case Map<?, ?> map -> appendMap(builder, (Map<String, Object>) map);
            default -> builder.add("$S", value.toString());
        }
    }

    private void appendList(CodeBlock.Builder builder, List<?> list) {
        if (list.isEmpty()) {
            builder.add("$T.of()", List.class);
            return;
        }
        builder.add("$T.asList(new Object[]{", Arrays.class);
        var iterator = list.iterator();
        while (iterator.hasNext()) {
            appendLiteral(builder, iterator.next());
            if (iterator.hasNext()) builder.add(", ");
        }
        builder.add("})");
    }

    private void appendMap(CodeBlock.Builder builder, Map<String, Object> map) {
        if (map.isEmpty()) {
            builder.add("$T.of()", Map.class);
            return;
        }
        builder.add("Stream.of(\n");
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            builder.add("  new $T.SimpleImmutableEntry<>($S, ", AbstractMap.class, entry.getKey());
            appendLiteral(builder, entry.getValue());
            builder.add(")");
            if (iterator.hasNext()) builder.add(",\n");
        }
        builder.add("\n).collect($T.toMap(Map.Entry::getKey, Map.Entry::getValue, (a,b)->b, $T::new))",
            java.util.stream.Collectors.class, LinkedHashMap.class);
    }

    private Object getConfigValue(Map<String, Object> map, String path) {
        if (map == null || path.isBlank()) return null;

        var parts = path.split("\\.");
        Object current = map;

        for (var part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                var value = currentMap.get(part);
                if (value != null) {
                    current = value;
                    continue;
                }

                var kebabPart = toKebabCase(part);
                value = currentMap.get(kebabPart);
                if (value != null) {
                    current = value;
                    continue;
                }

                return null;
            } else {
                return null;
            }
        }
        return current;
    }

    private static String toKebabCase(String input) {
        if (input == null || input.isEmpty()) return input;

        var result = new StringBuilder();
        char[] chars = input.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = chars[i - 1];
                    boolean prevIsLowerOrDigit = Character.isLowerCase(prev) || Character.isDigit(prev);
                    boolean nextIsLower = (i + 1 < chars.length) && Character.isLowerCase(chars[i + 1]);

                    if (prevIsLowerOrDigit || (Character.isUpperCase(prev) && nextIsLower)) {
                        result.append('-');
                    }
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
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
