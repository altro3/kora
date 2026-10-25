package io.koraframework.config.aot.gradle;

import com.palantir.javapoet.*;
import io.github.classgraph.*;
import io.koraframework.config.aot.*;
import io.koraframework.config.common.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Optional;

import javax.lang.model.element.Modifier;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

@CacheableTask
public abstract class KoraAotConfigTask extends DefaultTask {
    private static final int MAX_FIELDS_PER_CHUNK = 350;
    private static final String ANNOTATION_NAME = "io.koraframework.config.common.annotation.ConfigSource";

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getJavaSourceDirs();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasspathResources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExternalDependencies();

    @Input
    @Optional
    public abstract Property<String> getConfigResource();

    @Input
    @Optional
    public abstract Property<String> getConfigFile();

    @Input
    public abstract Property<String> getConfigFormat();

    @OutputDirectory
    public abstract DirectoryProperty getOutputSourceDirectory();

    @TaskAction
    public void generate() throws Exception {
        File outputDir = getOutputSourceDirectory().get().getAsFile();
        if (outputDir.exists()) {
            delete(outputDir);
        }
        outputDir.mkdirs();

        var targetClasses = new LinkedHashSet<String>();
        var packagePattern = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9._]+);");
        var interfacePattern = Pattern.compile("public\\s+interface\\s+([a-zA-Z0-9_]+)");

        for (var srcDir : getJavaSourceDirs().getFiles()) {
            if (!srcDir.exists()) continue;
            try (var stream = Files.walk(srcDir.toPath())) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        String content = Files.readString(path);
                        if (content.contains("@ConfigSource")) {
                            Matcher pkgMatcher = packagePattern.matcher(content);
                            String packageName = pkgMatcher.find() ? pkgMatcher.group(1) : "";
                            Matcher intMatcher = interfacePattern.matcher(content);
                            if (intMatcher.find()) {
                                String interfaceName = intMatcher.group(1);
                                String fullClassName = packageName.isEmpty() ? interfaceName : packageName + "." + interfaceName;
                                targetClasses.add(fullClassName);
                            }
                        }
                    } catch (IOException ignored) {}
                });
            }
        }

        var classpathFiles = new ArrayList<File>();
        for (var file : getClasspathResources().getFiles()) {
            if (file.exists()) classpathFiles.add(file);
        }
        for (var file : getExternalDependencies().getFiles()) {
            if (file.exists()) classpathFiles.add(file);
        }

        if (!classpathFiles.isEmpty()) {
            try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(classpathFiles)
                .enableAnnotationInfo()
                .scan()) {
                targetClasses.addAll(scanResult.getClassesWithAnnotation(ANNOTATION_NAME).getNames());
            }
        }

        if (targetClasses.isEmpty()) {
            getLogger().lifecycle("[Kora-Aot] No classes with @ConfigSource found. Skipping AOT generation.");
            return;
        }
        getLogger().lifecycle("[Kora-Aot] Found {} configuration classes to compile.", targetClasses.size());

        List<URL> urls = new ArrayList<>();
        for (File file : classpathFiles) {
            urls.add(file.toURI().toURL());
        }
        URLClassLoader projectClassLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());

        String format = getConfigFormat().getOrElse("HOCON");
        AotConfigParser configParser = "YAML".equalsIgnoreCase(format) ? new YamlAotParser() : new HoconAotParser();
        configParser.loadAndMerge(projectClassLoader, getConfigResource().getOrNull(), getConfigFile().getOrNull());

        generateMonolithicAotModule(targetClasses, configParser, outputDir, projectClassLoader);
    }

    private void generateMonolithicAotModule(Set<String> targetClasses, AotConfigParser parser, File outputDir, ClassLoader classLoader) throws Exception {
        String packageName = "io.koraframework.config.aot.generated";
        String moduleName = "KoraAotConfigApplicationModule";

        var resolversClass = ClassName.get("io.koraframework.config.aot", "AotConfigResolver");
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
            for (var entry : globalFlattenedValues.entrySet()) {
                staticInitBlock.add("overrides.put($S, $S);\n", entry.getKey(), entry.getValue());
            }
            staticInitBlock.add("$T.putRuntimeOverrides(overrides);\n", resolversClass);
        }

        typeBuilder.addType(TypeSpec.classBuilder("Impl")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get(packageName, moduleName))
            .addStaticBlock(staticInitBlock.build())
            .build());

        class ChunkContext {
            int totalFields = 0;
            int currentChunkMethodIndex = 0;
            CodeBlock.Builder currentChunkBuilder = CodeBlock.builder();
            final List<MethodSpec> chunkMethods = new ArrayList<>();
        }
        final ChunkContext ctx = new ChunkContext();

        for (String fullClassName : targetClasses) {
            Class<?> configClass = Class.forName(fullClassName, false, classLoader);
            int lastDot = fullClassName.lastIndexOf('.');
            String simpleClassName = lastDot > 0 ? fullClassName.substring(lastDot + 1) : fullClassName;

            String path = "";
            try {
                var annotation = configClass.getAnnotation(io.koraframework.config.common.annotation.ConfigSource.class);
                if (annotation != null) {
                    path = annotation.value();
                }
            } catch (Exception ignored) {}

            String factoryMethodName = Character.toLowerCase(simpleClassName.charAt(0)) + simpleClassName.substring(1);
            var methodBuilder = MethodSpec.methodBuilder(factoryMethodName)
                .returns(ClassName.get(configClass))
                .addAnnotation(defaultComponentAnno)
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);

            var mapperInterface = ClassName.get("io.koraframework.config.common.mapper", "ConfigValueMapper");
            methodBuilder.addParameter(ParameterizedTypeName.get(mapperInterface, ClassName.get(configClass)), "mapper");

            var initCode = CodeBlock.builder();
            initCode.add("var origin = new $T(\n  new $T(\"aot-compiled\"),\n  $T.root()\n);\n\n",
                ClassName.get("io.koraframework.config.common.impl", "SimpleConfigValueOrigin"),
                ClassName.get("io.koraframework.config.common.origin", "SimpleConfigOrigin"),
                ClassName.get("io.koraframework.config.common", "ConfigValuePath"));
            initCode.add("var rootMap = new $T<String, $T<?>>();\n", LinkedHashMap.class, ConfigValue.class);

            int chunkStartIndex = ctx.currentChunkMethodIndex;
            int fieldsBeforeClass = ctx.totalFields;

            Consumer<CodeBlock> chunkAppender = (codeLine) -> {
                ctx.currentChunkBuilder.add(codeLine);
                ctx.totalFields++;
                if (ctx.totalFields % MAX_FIELDS_PER_CHUNK == 0) {
                    addChunkMethod(ctx.chunkMethods, factoryMethodName, ctx.currentChunkMethodIndex, ctx.currentChunkBuilder);
                    ctx.currentChunkBuilder = CodeBlock.builder();
                    ctx.currentChunkMethodIndex++;
                }
            };

            generateFieldResolversReflection(chunkAppender, configClass, path, parser, new HashSet<>());

            if (ctx.totalFields > fieldsBeforeClass) {
                if (ctx.totalFields % MAX_FIELDS_PER_CHUNK != 0 || fieldsBeforeClass % MAX_FIELDS_PER_CHUNK == 0) {
                    addChunkMethod(ctx.chunkMethods, factoryMethodName, ctx.currentChunkMethodIndex, ctx.currentChunkBuilder);
                    ctx.currentChunkBuilder = CodeBlock.builder();
                    ctx.currentChunkMethodIndex++;
                }
            }

            int chunkEndIndex = ctx.currentChunkMethodIndex;
            for (int i = chunkStartIndex; i < chunkEndIndex; i++) {
                initCode.add("$L_chunk$L(rootMap);\n", factoryMethodName, i);
            }

            initCode.add("\nvar finalObjectValue = new $T(origin, rootMap);\n", ClassName.get("io.koraframework.config.common.ConfigValue", "ObjectValue"));

            if (path.isBlank()) {
                initCode.add("var targetValue = finalObjectValue;\n");
            } else {
                String[] pathParts = path.split("\\.");
                initCode.add("var targetValue = finalObjectValue");
                for (int i = 0; i < pathParts.length; i++) {
                    initCode.add(".get($T.get($S))", ClassName.get("io.koraframework.config.common", "PathElement"), pathParts[i]);
                    if (i < pathParts.length - 1) initCode.add(".asObject()");
                }
                initCode.add(";\n");
            }

            initCode.add("return mapper.map(targetValue);\n");
            methodBuilder.addCode(initCode.build());
            typeBuilder.addMethod(methodBuilder.build());
        }

        for (MethodSpec chunk : ctx.chunkMethods) {
            typeBuilder.addMethod(chunk);
        }

        JavaFile javaFile = JavaFile.builder(packageName, typeBuilder.build())
            .addStaticImport(resolversClass, "*")
            .build();

        javaFile.writeTo(outputDir);
    }

    private void addChunkMethod(List<MethodSpec> chunkMethods, String methodName, int index, CodeBlock.Builder builder) {
        chunkMethods.add(MethodSpec.methodBuilder(methodName + "_chunk" + index)
            .addModifiers(Modifier.PRIVATE)
            .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ParameterizedTypeName.get(ClassName.get(ConfigValue.class), WildcardTypeName.subtypeOf(Object.class))), "rootMap")
            .addCode(builder.build())
            .build());
    }

    private void generateFieldResolversReflection(Consumer<CodeBlock> chunkAppender, Class<?> configClass, String currentPath, AotConfigParser parser, Set<Class<?>> visited) {
        if (configClass == null || !visited.add(configClass)) return;
        try {
            for (Method method : configClass.getMethods()) {
                if (method.isDefault() || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() > 0) continue;
                if (method.getDeclaringClass() == Object.class) continue;

                String fieldName = method.getName();
                var fullKey = currentPath.isBlank() ? fieldName : currentPath + "." + fieldName;

                Class<?> returnType = method.getReturnType();

                // Если это вложенный интерфейс конфигурации (вложенный объект)
                boolean isNestedInterface = returnType.isInterface()
                    && !returnType.getName().startsWith("java.lang.")
                    && !returnType.getName().startsWith("java.util.")
                    && !returnType.getName().startsWith("java.time.");

                if (isNestedInterface) {
                    chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, null, null, false, null);\n", currentPath, fieldName));
                    generateFieldResolversReflection(chunkAppender, returnType, fullKey, parser, visited);
                    continue;
                }

                Object baseVal = parser.getConfigValue(fullKey);
                CodeBlock baseValBlock;
                CodeBlock fallbackBlock = CodeBlock.of("null");

                if (baseVal instanceof HoconAotParser.FallbackHolder holder) {
                    baseValBlock = CodeBlock.of("$S", holder.rawExpression);
                    if (holder.fallbackValue != null) fallbackBlock = convertToCodeBlock(holder.fallbackValue);
                } else {
                    baseValBlock = convertToCodeBlock(baseVal);
                }

                List<String> relaxedVariants = PathElement.get(fieldName).relaxedNames();
                CodeBlock.Builder listLiteral = CodeBlock.builder().add("$T.of(", List.class);
                for (int i = 0; i < relaxedVariants.size(); i++) {
                    listLiteral.add("$S", relaxedVariants.get(i));
                    if (i < relaxedVariants.size() - 1) listLiteral.add(", ");
                }
                listLiteral.add(")");

                boolean hasInterfaceDefault = false;

                chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, $L, $L, $L, $L);\n",
                    currentPath, fieldName, baseValBlock, fallbackBlock, hasInterfaceDefault, listLiteral.build()
                ));
            }
        } finally {
            visited.remove(configClass);
        }
    }

    private CodeBlock convertToCodeBlock(Object koraValue) {
        if (koraValue == null) return CodeBlock.of("null");
        if (koraValue instanceof String) return CodeBlock.of("$S", koraValue);
        if (koraValue instanceof Long) return CodeBlock.of("$LL", koraValue);
        if (koraValue instanceof Number) return CodeBlock.of("$L", koraValue);
        if (koraValue instanceof Boolean) return CodeBlock.of("$L", koraValue);
        if (koraValue instanceof List) {
            List<?> list = (List<?>) koraValue;
            CodeBlock.Builder builder = CodeBlock.builder().add("new Object[] { ");
            for (int i = 0; i < list.size(); i++) {
                builder.add(convertToCodeBlock(list.get(i)));
                if (i < list.size() - 1) builder.add(", ");
            }
            return builder.add(" }").build();
        }
        if (koraValue instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) koraValue;
            CodeBlock.Builder builder = CodeBlock.builder().add("$T.ofEntries(\n", Map.class);
            List<? extends Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<?, ?> entry = entries.get(i);
                builder.add("    $T.entry($S, $L)", Map.class, String.valueOf(entry.getKey()), convertToCodeBlock(entry.getValue()));
                if (i < entries.size() - 1) builder.add(",\n");
            }
            return builder.add("\n)").build();
        }
        return CodeBlock.of("$S", koraValue.toString());
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {delete(f);}
            }
        }
        file.delete();
    }

    private void collectFlattenedProperties(AotConfigParser parser, String currentPath, Map<String, String> result) {
        Object baseVal = parser.getConfigValue(currentPath);
        if (baseVal == null) return;
        if (baseVal instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) baseVal;
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String nextPath = currentPath.isBlank() ? key : currentPath + "." + key;
                collectFlattenedProperties(parser, nextPath, result);
            }
        } else if (baseVal instanceof List) {
            List<?> list = (List<?>) baseVal;
            for (int i = 0; i < list.size(); i++) {
                collectFlattenedProperties(parser, currentPath + "." + i, result);
            }
        } else if (baseVal instanceof HoconAotParser.FallbackHolder holder) {
            if (holder.fallbackValue != null) result.put(currentPath, String.valueOf(holder.fallbackValue));
        } else {
            result.put(currentPath, String.valueOf(baseVal));
        }
    }
}
