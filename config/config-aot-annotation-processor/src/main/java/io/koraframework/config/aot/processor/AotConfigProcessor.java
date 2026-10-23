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
import java.util.stream.*;

import static io.koraframework.config.common.aot.AotConfigResolvers.toCamelCase;
import static io.koraframework.config.common.aot.AotConfigResolvers.toKebabCase;

public class AotConfigProcessor extends AbstractKoraProcessor {
    private final Map<String, Object> baseConfig = new HashMap<>();
    private final Map<String, Map<String, Object>> profileConfigs = new HashMap<>();
    private boolean isConfigLoaded = false;
    private final Set<ClassName> generatedModules = new LinkedHashSet<>();

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
            var parsed = HoconAotParser.parse(hoconBase, null);
            if (parsed != null) baseConfig.putAll(parsed);
        } else if (!yamlBase.isBlank()) {
            var parsed = YamlAotParser.parse(yamlBase, null);
            if (parsed != null) baseConfig.putAll(parsed);
        }

        var discoveredProfiles = discoverProfileFiles(packageName);
        for (var entry : discoveredProfiles.entrySet()) {
            var profileName = entry.getKey();
            var files = entry.getValue();
            var pConfig = new HashMap<String, Object>();

            for (var file : files) {
                Map<String, Object> parsed;
                if (file.filename().endsWith(".conf")) {
                    parsed = HoconAotParser.parse(hoconBase, file.content());
                } else {
                    parsed = YamlAotParser.parse(yamlBase, file.content());
                }
                if (parsed != null) pConfig.putAll(parsed);
            }
            profileConfigs.put(profileName, pConfig);
        }
    }

    private Map<String, List<ProfileFile>> discoverProfileFiles(String packageName) {
        var result = new HashMap<String, List<ProfileFile>>();
        var pattern = Pattern.compile("application-(.+)\\.(conf|yaml|yml)");
        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            String packagePath = packageName.replace('.', '/');

            var searchPaths = List.of(
                runDir.resolve("build/in-test-generated/classes").resolve(packagePath),
                runDir.resolve("build/in-test-generated/sources").resolve(packagePath),
                runDir.resolve("src/main/resources")
            );

            for (var searchPath : searchPaths) {
                if (Files.exists(searchPath)) {
                    try (var stream = Files.walk(searchPath, 1)) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            var filename = p.getFileName().toString();
                            var matcher = pattern.matcher(filename);
                            if (matcher.matches()) {
                                var profile = matcher.group(1);
                                try {
                                    var content = Files.readString(p, StandardCharsets.UTF_8);
                                    var list = result.computeIfAbsent(profile, _ -> new ArrayList<>());
                                    boolean alreadyExists = list.stream().anyMatch(f -> f.filename().equals(filename));
                                    if (!alreadyExists) {
                                        list.add(new ProfileFile(filename, content));
                                    }
                                } catch (IOException ignored) {}
                            }
                        });
                    }
                }
            }
        } catch (IOException ignored) {}
        return result;
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

        for (var annotated : annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of())) {
            var config = (TypeElement) annotated.element();
            var moduleName = config.getSimpleName() + "AotModule";
            var packageElement = elements.getPackageOf(config);
            var packageName = packageElement.getQualifiedName().toString();
            var mapperSimpleName = "$" + config.getSimpleName() + "_ConfigValueMapper";
            var implClassName = ClassName.get(packageName, mapperSimpleName, config.getSimpleName() + "_Impl");
            var mapperClassName = ClassName.get(packageName, mapperSimpleName);

            baseConfig.clear();
            profileConfigs.clear();
            isConfigLoaded = false;
            loadAllConfigsAtCompileTime(packageName);

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
                    var envKey = toKebabCase(fullKey).toUpperCase().replace('.', '_').replace('-', '_');
                    var sysKey = toKebabCase(fullKey);

                    var baseVal = getConfigValue(baseConfig, fullKey);
                    var baseValBuilder = CodeBlock.builder();
                    appendLiteral(baseValBuilder, baseVal);
                    var baseValCode = baseValBuilder.build();

                    var isFirstProfile = true;
                    var hasProfiles = false;
                    var profileValuesBuilder = CodeBlock.builder().add("new Object[] {");

                    for (var entry : profileConfigs.entrySet()) {
                        var profileVal = getConfigValue(entry.getValue(), fullKey);

                        if (profileVal != null && !profileVal.equals(baseVal)) {
                            hasProfiles = true;
                            if (!isFirstProfile) profileValuesBuilder.add(", ");
                            isFirstProfile = false;
                            profileValuesBuilder.add("$S, ", entry.getKey());
                            appendLiteral(profileValuesBuilder, profileVal);
                        }
                    }
                    profileValuesBuilder.add("}");
                    var profileValuesCode = hasProfiles ? profileValuesBuilder.build() : CodeBlock.of("null");

                    boolean needsFullParams = hasProfiles || baseVal != null;

                    var hasDefault = false;
                    for (var methodElement : javax.lang.model.util.ElementFilter.methodsIn(config.getEnclosedElements())) {
                        if (methodElement.getSimpleName().toString().equals(field.name()) && methodElement.getModifiers().contains(Modifier.DEFAULT)) {
                            hasDefault = true;
                            break;
                        }
                    }

                    var defExpr = interfaceHasAnyDefaults ? "DEFAULTS." + field.name() + "()" : null;
                    initCode.add("  ");

                    String intMethod = field.isNullable() ? "resolveNullableInt" : "resolveInt";
                    String boolMethod = field.isNullable() ? "resolveNullableBoolean" : "resolveBoolean";
                    String stringMethod = field.isNullable() ? "resolveNullableString" : "resolveString";
                    String objectMethod = field.isNullable() ? "resolveNullableObject" : "resolveObject";

                    var baseValBlock = baseVal != null ? baseValCode : CodeBlock.of("null");
                    var profilesBlock = hasProfiles ? profileValuesCode : CodeBlock.of("null");

                    if (field.typeName().equals(TypeName.INT) || field.typeName().equals(TypeName.INT.box())) {
                        var fallback = defExpr != null ? defExpr : "0";
                        initCode.add("$L($S, $S, $L, $L, $L, $L)", intMethod, envKey, sysKey, profilesBlock, baseValBlock, hasDefault, fallback);
                    } else if (field.typeName().equals(TypeName.BOOLEAN) || field.typeName().equals(TypeName.BOOLEAN.box())) {
                        var fallback = defExpr != null ? defExpr : "false";
                        initCode.add("$L($S, $S, $L, $L, $L, $L)", boolMethod, envKey, sysKey, profilesBlock, baseValBlock, hasDefault, fallback);
                    } else if (field.typeName().equals(ClassName.get(String.class))) {
                        var fallback = defExpr != null ? defExpr : "null";
                        initCode.add("$L($S, $S, $L, $L, $L, $L)", stringMethod, envKey, sysKey, profilesBlock, baseValBlock, hasDefault, fallback);
                    } else {
                        var fallback = defExpr != null ? defExpr : "null";
                        initCode.add("($T) $L($S, $S, $L, $L, $L, $L)", field.typeName(), objectMethod, envKey, sysKey, profilesBlock, baseValBlock, hasDefault, fallback);
                    }

                    if (i < configFields.size() - 1) initCode.add(",\n");
                }
                initCode.add("\n);\n");
                method.addCode(initCode.build());
            }

            typeBuilder.addMethod(method.build());

            var javaFileBuilder = JavaFile.builder(packageName, typeBuilder.build())
                .addStaticImport(resolversClass, "*");

            if (interfaceHasAnyDefaults) {
                javaFileBuilder.addStaticImport(mapperClassName, "DEFAULTS");
            }

            var javaFile = javaFileBuilder.build();
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
            Collectors.class, LinkedHashMap.class);
    }

    private Object getConfigValue(Map<String, Object> map, String path) {
        if (map == null || path.isBlank()) return null;

        var parts = path.split("\\.");
        Object current = map;
        boolean walkedSuccessfully = true;

        for (var part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                var value = currentMap.get(part);

                if (value == null) {
                    var kebab = toKebabCase(part);
                    if (!kebab.equals(part)) value = currentMap.get(kebab);
                }

                if (value == null) {
                    var camel = toCamelCase(part);
                    if (!camel.equals(part)) value = currentMap.get(camel);
                }

                if (value == null) {
                    String cleanPart = part.toLowerCase().replace("-", "");
                    for (var entry : currentMap.entrySet()) {
                        String cleanKey = entry.getKey().toString().toLowerCase().replace("-", "");
                        if (cleanKey.equals(cleanPart)) {
                            value = entry.getValue();
                            break;
                        }
                    }
                }

                if (value == null) {
                    walkedSuccessfully = false;
                    break;
                }
                current = value;
            } else {
                walkedSuccessfully = false;
                break;
            }
        }

        if (walkedSuccessfully && current != map) {
            return current;
        }

        Object flatValue = map.get(path);
        if (flatValue != null) return flatValue;

        var flatKebab = toKebabCase(path);
        if (!flatKebab.equals(path)) {
            flatValue = map.get(flatKebab);
            if (flatValue != null) return flatValue;
        }

        var flatCamel = toCamelCase(path);
        if (!flatCamel.equals(path)) {
            flatValue = map.get(flatCamel);
            if (flatValue != null) return flatValue;
        }

        var cleanLowerPath = path.toLowerCase().replace("-", "");
        flatValue = map.get(cleanLowerPath);
        if (flatValue != null) return flatValue;

        for (var entry : map.entrySet()) {
            var cleanKey = entry.getKey().toLowerCase().replace("-", "");
            if (cleanKey.equals(cleanLowerPath)) {
                return entry.getValue();
            }
        }

        return null;
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
