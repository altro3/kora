package io.koraframework.config.aot.gradle;

import com.palantir.javapoet.*;
import io.github.classgraph.*;
import io.koraframework.config.aot.*;
import io.koraframework.config.common.*;

import javax.lang.model.element.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

public class KoraAotGenerator {
    private static final int MAX_FIELDS_PER_CHUNK = 350;
    private static final String ANNOTATION_NAME = "io.koraframework.config.common.annotation.ConfigSource";

    private final Set<File> javaSourceDirs;
    private final List<File> classpathFiles;
    private final String configResource;
    private final String configFile;
    private final String configFormat;
    private final File outputDir;
    private final AppLogger logger;

    public KoraAotGenerator(Set<File> javaSourceDirs,
                            List<File> classpathFiles,
                            String configResource,
                            String configFile,
                            String configFormat,
                            File outputDir,
                            AppLogger logger) {
        this.javaSourceDirs = javaSourceDirs;
        this.classpathFiles = classpathFiles;
        this.configResource = configResource;
        this.configFile = configFile;
        this.configFormat = configFormat;
        this.outputDir = outputDir;
        this.logger = logger;
    }

    public void execute() throws Exception {
        if (outputDir.exists()) {
            delete(outputDir);
        }
        outputDir.mkdirs();

        var targetClasses = new LinkedHashSet<String>();

        scanSources(targetClasses);

        var urls = new ArrayList<URL>();
        for (var file : classpathFiles) {
            urls.add(file.toURI().toURL());
        }

        try (var scanResult = new ClassGraph()
            .overrideClasspath(classpathFiles)
            .enableAnnotationInfo()
            .enableMethodInfo()
            .enableClassInfo()
            .enableExternalClasses()
            .scan()) {

            if (!classpathFiles.isEmpty()) {
                targetClasses.addAll(scanResult.getClassesWithAnnotation(ANNOTATION_NAME).getNames());
            }

            if (targetClasses.isEmpty()) {
                logger.lifecycle("[Kora-Aot] No classes with @ConfigSource found. Skipping AOT generation.");
                return;
            }
            logger.lifecycle("[Kora-Aot] Found " + targetClasses.size() + " configuration classes to compile.");

            try (var projectClassLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
                var format = configFormat;
                var file = configFile;

                if (format == null || format.isBlank()) {
                    if (file != null && !file.isBlank()) {
                        var lower = file.toLowerCase();
                        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) format = "YAML";
                        else if (lower.endsWith(".conf") || lower.endsWith(".json")) format = "HOCON";
                    } else {
                        var resource = configResource != null ? configResource : "application";
                        if (projectClassLoader.getResource(resource + ".yaml") != null ||
                            projectClassLoader.getResource(resource + ".yml") != null) {
                            format = "YAML";
                        } else if (projectClassLoader.getResource(resource + ".conf") != null ||
                            projectClassLoader.getResource(resource + ".json") != null) {
                            format = "HOCON";
                        }
                    }
                }

                if (format == null || format.isBlank()) {
                    format = "HOCON";
                }

                var configParser = "YAML".equalsIgnoreCase(format) ? new YamlAotParser() : new HoconAotParser();
                configParser.loadAndMerge(projectClassLoader, configResource, configFile);

                generateMonolithicAotModule(targetClasses, configParser, outputDir, scanResult);
            }
        }
    }

    private void scanSources(Set<String> targetClasses) {
        var packagePattern = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9._]+);");
        var interfacePattern = Pattern.compile("public\\s+interface\\s+([a-zA-Z0-9_]+)");

        for (var srcDir : javaSourceDirs) {
            if (!srcDir.exists()) continue;
            try (var stream = Files.walk(srcDir.toPath())) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        var content = Files.readString(path);
                        if (content.contains("@ConfigSource")) {
                            var pkgMatcher = packagePattern.matcher(content);
                            var packageName = pkgMatcher.find() ? pkgMatcher.group(1) : "";
                            var intMatcher = interfacePattern.matcher(content);
                            if (intMatcher.find()) {
                                var interfaceName = intMatcher.group(1);
                                var fullClassName = packageName.isEmpty() ? interfaceName : packageName + "." + interfaceName;
                                targetClasses.add(fullClassName);
                            }
                        }
                    } catch (IOException _) {}
                });
            } catch (IOException _) {}
        }
    }

    private void generateMonolithicAotModule(Set<String> targetClasses, AotConfigParser parser, File outputDir, ScanResult scanResult) throws Exception {
        var packageName = "io.koraframework.config.aot.generated";
        var moduleName = "KoraAotConfigApplicationModule";

        var evaluatorClass = ClassName.get("io.koraframework.config.aot", "AotConfigEvaluator");
        var defaultComponentAnno = ClassName.get("io.koraframework.common.annotation", "DefaultComponent");
        var moduleAnno = ClassName.get("io.koraframework.common.annotation", "Module");

        var typeBuilder = TypeSpec.interfaceBuilder(moduleName)
            .addAnnotation(moduleAnno)
            .addModifiers(Modifier.PUBLIC);

        var globalFlattenedValues = new LinkedHashMap<String, String>();
        collectFlattenedProperties(parser, "", globalFlattenedValues);

        var staticInitBlock = CodeBlock.builder();
        if (!globalFlattenedValues.isEmpty()) {
            staticInitBlock.add("var overrides = new $T<$T, $T>();\n", LinkedHashMap.class, String.class, String.class);

            var entries = new ArrayList<>(globalFlattenedValues.entrySet());
            var currentChunkIndex = 0;
            var currentChunkBuilder = CodeBlock.builder();
            var chunkMethods = new ArrayList<MethodSpec>();

            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                currentChunkBuilder.add("overrides.put($S, $S);\n", entry.getKey(), entry.getValue());

                if ((i + 1) % MAX_FIELDS_PER_CHUNK == 0 || (i + 1) == entries.size()) {
                    var chunkMethodName = "initOverrides_chunk" + currentChunkIndex;

                    chunkMethods.add(MethodSpec.methodBuilder(chunkMethodName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(String.class)), "overrides")
                        .addCode(currentChunkBuilder.build())
                        .build());

                    staticInitBlock.add("$L(overrides);\n", chunkMethodName);

                    currentChunkBuilder = CodeBlock.builder();
                    currentChunkIndex++;
                }
            }

            staticInitBlock.add("putRuntimeOverrides(overrides);\n");

            var implBuilder = TypeSpec.classBuilder("Impl")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(ClassName.get(packageName, moduleName))
                .addStaticBlock(staticInitBlock.build());

            for (var chunkMethod : chunkMethods) {
                implBuilder.addMethod(chunkMethod);
            }

            typeBuilder.addType(implBuilder.build());
        } else {
            typeBuilder.addType(TypeSpec.classBuilder("Impl")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(ClassName.get(packageName, moduleName))
                .build());
        }

        final class ChunkContext {
            int totalFields = 0;
            int currentChunkMethodIndex = 0;
            CodeBlock.Builder currentChunkBuilder = CodeBlock.builder();
            final List<MethodSpec> chunkMethods = new ArrayList<>();
        }
        var ctx = new ChunkContext();

        for (var fullClassName : targetClasses) {
            var configClassInfo = scanResult.getClassInfo(fullClassName);
            if (configClassInfo == null) continue;

            var simpleClassName = configClassInfo.getSimpleName();
            var path = "";
            var annotationInfo = configClassInfo.getAnnotationInfo(ANNOTATION_NAME);
            if (annotationInfo != null) {
                var paramValues = annotationInfo.getParameterValues();
                var valueParam = paramValues.get("value");
                if (valueParam != null) {
                    path = String.valueOf(valueParam.getValue());
                }
            }

            var factoryMethodName = Character.toLowerCase(simpleClassName.charAt(0)) + simpleClassName.substring(1);
            var methodBuilder = MethodSpec.methodBuilder(factoryMethodName)
                .returns(ClassName.get(configClassInfo.getPackageName(), configClassInfo.getSimpleName()))
                .addAnnotation(defaultComponentAnno)
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);

            var mapperInterface = ClassName.get("io.koraframework.config.common.mapper", "ConfigValueMapper");
            methodBuilder.addParameter(ParameterizedTypeName.get(mapperInterface, ClassName.get(configClassInfo.getPackageName(), configClassInfo.getSimpleName())), "mapper");

            var initCode = CodeBlock.builder();
            initCode.add("var origin = new $T(\n  new $T(\"aot-compiled\"),\n  $T.root()\n);\n\n",
                ClassName.get("io.koraframework.config.common.impl", "SimpleConfigValueOrigin"),
                ClassName.get("io.koraframework.config.common.origin", "SimpleConfigOrigin"),
                ClassName.get("io.koraframework.config.common", "ConfigValuePath"));
            initCode.add("var rootMap = new $T<String, $T<?>>();\n", LinkedHashMap.class, ConfigValue.class);

            var chunkStartIndex = ctx.currentChunkMethodIndex;
            var fieldsBeforeClass = ctx.totalFields;

            Consumer<CodeBlock> chunkAppender = (codeLine) -> {
                ctx.currentChunkBuilder.add(codeLine);
                ctx.totalFields++;
                if (ctx.totalFields % MAX_FIELDS_PER_CHUNK == 0) {
                    addChunkMethod(ctx.chunkMethods, factoryMethodName, ctx.currentChunkMethodIndex, ctx.currentChunkBuilder);
                    ctx.currentChunkBuilder = CodeBlock.builder();
                    ctx.currentChunkMethodIndex++;
                }
            };

            generateFieldResolversReflection(chunkAppender, configClassInfo, path, parser, new HashSet<>(), scanResult);

            if (ctx.totalFields > fieldsBeforeClass || ctx.currentChunkMethodIndex == chunkStartIndex) {
                addChunkMethod(ctx.chunkMethods, factoryMethodName, ctx.currentChunkMethodIndex, ctx.currentChunkBuilder);
                ctx.currentChunkBuilder = CodeBlock.builder();
                ctx.currentChunkMethodIndex++;
            }

            var chunkEndIndex = ctx.currentChunkMethodIndex;
            for (var i = chunkStartIndex; i < chunkEndIndex; i++) {
                initCode.add("$L_chunk$L(rootMap, origin);\n", factoryMethodName, i);
            }

            initCode.add("\nvar finalObjectValue = new $T(origin, rootMap);\n", ClassName.get("io.koraframework.config.common.ConfigValue", "ObjectValue"));

            if (path.isBlank()) {
                initCode.add("var targetValue = finalObjectValue;\n");
            } else {
                var pathParts = path.split("\\.");
                initCode.add("var targetValue = finalObjectValue");
                for (var i = 0; i < pathParts.length; i++) {
                    initCode.add(".get($T.get($S))", ClassName.get("io.koraframework.config.common", "PathElement"), pathParts[i]);
                    if (i < pathParts.length - 1) initCode.add(".asObject()");
                }
                initCode.add(";\n");
            }

            initCode.add("return mapper.map(targetValue);\n");
            methodBuilder.addCode(initCode.build());
            typeBuilder.addMethod(methodBuilder.build());
        }

        for (var chunk : ctx.chunkMethods) {
            typeBuilder.addMethod(chunk);
        }

        var javaFile = JavaFile.builder(packageName, typeBuilder.build())
            .addStaticImport(evaluatorClass, "*")
            .build();

        javaFile.writeTo(outputDir);
    }

    private void addChunkMethod(List<MethodSpec> chunkMethods, String methodName, int index, CodeBlock.Builder builder) {
        chunkMethods.add(MethodSpec.methodBuilder(methodName + "_chunk" + index)
            .addModifiers(Modifier.PRIVATE)
            .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ParameterizedTypeName.get(ClassName.get(ConfigValue.class), WildcardTypeName.subtypeOf(Object.class))), "rootMap")
            .addParameter(ClassName.get("io.koraframework.config.common", "ConfigValueOrigin"), "origin")
            .addCode(builder.build())
            .build());
    }

    private void generateFieldResolversReflection(Consumer<CodeBlock> chunkAppender, ClassInfo configClassInfo, String currentPath, AotConfigParser parser, Set<String> visited, ScanResult scanResult) {
        generateFieldResolversReflectionRec(chunkAppender, configClassInfo, currentPath, parser, visited, scanResult, "rootMap");
    }

    private void generateFieldResolversReflectionRec(Consumer<CodeBlock> chunkAppender, ClassInfo configClassInfo, String currentPath, AotConfigParser parser, Set<String> visited, ScanResult scanResult, String currentMapName) {
        if (configClassInfo == null || !visited.add(configClassInfo.getName())) return;
        try {
            var nullValueClass = ClassName.get("io.koraframework.config.common", "ConfigValue", "NullValue");
            var objectValueClass = ClassName.get("io.koraframework.config.common", "ConfigValue", "ObjectValue");
            var linkedHashMapClass = ClassName.get("java.util", "LinkedHashMap");

            for (var methodInfo : configClassInfo.getMethodInfo()) {
                if (methodInfo.isStatic() || !methodInfo.isPublic()) continue;
                if (methodInfo.getParameterInfo().length > 0) continue;

                var fieldName = methodInfo.getName();
                if ("toString".equals(fieldName) || "hashCode".equals(fieldName) || "getClass".equals(fieldName)) continue;

                var fullKey = currentPath.isBlank() ? fieldName : currentPath + "." + fieldName;
                var returnTypeSignature = methodInfo.getTypeDescriptor().getResultType().toString();

                var isNestedInterface = false;
                var returnTypeInfo = scanResult.getClassInfo(returnTypeSignature);

                if (returnTypeInfo != null && returnTypeInfo.isInterface()) {
                    if (!returnTypeSignature.startsWith("java.lang.") &&
                        !returnTypeSignature.startsWith("java.util.") &&
                        !returnTypeSignature.startsWith("java.time.")) {
                        isNestedInterface = true;
                    }
                }

                if (isNestedInterface) {
                    var nestedMapName = fieldName + "Map";
                    chunkAppender.accept(CodeBlock.of("var $L = new $T<String, ConfigValue<?>>();\n", nestedMapName, linkedHashMapClass));

                    generateFieldResolversReflectionRec(chunkAppender, returnTypeInfo, fullKey, parser, visited, scanResult, nestedMapName);

                    chunkAppender.accept(CodeBlock.of("$L.put($S, new $T(new SimpleConfigValueOrigin(origin.config(), origin.path().child($S)), $L));\n",
                        currentMapName, fieldName, objectValueClass, fieldName, nestedMapName));
                    continue;
                }

                var baseVal = parser.getConfigValue(fullKey);
                CodeBlock statement = switch (baseVal) {
                    case null -> CodeBlock.of("new $T(origin.path().child($S))", nullValueClass, fieldName);
                    case String s -> CodeBlock.of("resolveString(origin.path().child($S), $S, $S)", fieldName, fullKey, s);
                    case Long l -> CodeBlock.of("resolveNumber(origin.path().child($S), $S, $LL)", fieldName, fullKey, l);
                    case Number n -> CodeBlock.of("resolveNumber(origin.path().child($S), $S, $L)", fieldName, fullKey, n);
                    case Boolean b -> CodeBlock.of("resolveBoolean(origin.path().child($S), $S, $L)", fieldName, fullKey, b);
                    case List<?> list -> {
                        var listBuilder = CodeBlock.builder().add("java.util.List.of(");
                        for (var i = 0; i < list.size(); i++) {
                            listBuilder.add("$S", String.valueOf(list.get(i)));
                            if (i < list.size() - 1) listBuilder.add(", ");
                        }
                        yield CodeBlock.of("resolveList(origin.path().child($S), $S, $L)", fieldName, fullKey, listBuilder.add(")").build());
                    }
                    case Map<?, ?> map -> {
                        var mapBuilder = CodeBlock.builder().add("java.util.Map.ofEntries(\n");
                        var entries = new ArrayList<>(map.entrySet());
                        for (var i = 0; i < entries.size(); i++) {
                            var entry = entries.get(i);
                            mapBuilder.add("    java.util.Map.entry($S, $S)", String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                            if (i < entries.size() - 1) mapBuilder.add(",\n");
                        }
                        yield CodeBlock.of("resolveMap(origin.path().child($S), $S, $L)", fieldName, fullKey, mapBuilder.add("\n)").build());
                    }
                    default -> CodeBlock.of("resolveString(origin.path().child($S), $S, $S)", fieldName, fullKey, baseVal.toString());
                };

                chunkAppender.accept(CodeBlock.of("$L.put($S, $L);\n", currentMapName, fieldName, statement));
            }
        } finally {
            visited.remove(configClassInfo.getName());
        }
    }

    private void collectFlattenedProperties(AotConfigParser parser, String currentPath, Map<String, String> result) {
        var baseVal = parser.getConfigValue(currentPath);
        switch (baseVal) {
            case null -> {}
            case Map<?, ?> map -> {
                for (var entry : map.entrySet()) {
                    var key = String.valueOf(entry.getKey());
                    var nextPath = currentPath.isBlank() ? key : currentPath + "." + key;
                    collectFlattenedProperties(parser, nextPath, result);
                }
            }
            case List<?> list -> {
                for (var i = 0; i < list.size(); i++) {
                    collectFlattenedProperties(parser, currentPath + "." + i, result);
                }
            }
            case HoconAotParser.FallbackHolder holder -> {
                if (holder.fallbackValue != null) result.put(currentPath, String.valueOf(holder.fallbackValue));
            }
            default -> result.put(currentPath, String.valueOf(baseVal));
        }
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            var files = file.listFiles();
            if (files != null) {
                for (var f : files) { delete(f); }
            }
        }
        file.delete();
    }

    public interface AppLogger {
        void lifecycle(String message);
    }
}
