package io.koraframework.config.aot.processor;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.*;
import io.koraframework.config.common.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.tools.Diagnostic.*;
import javax.tools.StandardLocation;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class AotConfigProcessor extends AbstractKoraProcessor {

    private static final int MAX_FIELDS_PER_CHUNK = 350;
    private static final String META_INF_PATH = "META-INF/kora/config-sources";

    private AotConfigParser configParser = null;
    private boolean isConfigLoaded = false;
    private final Set<String> processedConfigClasses = new HashSet<>();

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

        String customResource = processingEnv.getOptions().get("config.resource");
        if (customResource == null) customResource = System.getProperty("config.resource");

        String customFile = processingEnv.getOptions().get("config.file");
        if (customFile == null) customFile = System.getProperty("config.file");

        var urls = new ArrayList<URL>();

        ClassLoader processorClassLoader = getClass().getClassLoader();
        try {
            List<String> filesToSearch = List.of("reference.conf", "application.conf", "reference.yaml", "reference.yml", "application.yaml", "application.yml");
            for (String fileName : filesToSearch) {
                Enumeration<URL> resources = processorClassLoader.getResources(fileName);
                while (resources.hasMoreElements()) {
                    URL resourceUrl = resources.nextElement();
                    urls.add(resourceUrl);
                }
            }
        } catch (Exception ignored) {}

        try {
            var dummyResource = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "META-INF");
            var classOutputPath = Path.of(dummyResource.toUri()).getParent();

            var projectBuildDir = classOutputPath.getParent().getParent().getParent();
            var moduleDir = projectBuildDir.getParent();

            var searchPaths = List.of(
                classOutputPath,
                moduleDir.resolve("src/main/resources"),
                projectBuildDir.resolve("resources/main")
            );

            for (var path : searchPaths) {
                if (Files.exists(path)) {
                    var fileUrl = path.toUri().toURL();
                    if (!urls.contains(fileUrl)) {
                        urls.add(fileUrl);
                    }
                }
            }
        } catch (Exception e) {
            try {
                var runDir = Path.of(System.getProperty("user.dir", "."));
                var fallbackPaths = List.of(
                    runDir.resolve("src/main/resources"),
                    runDir.resolve("service-kora-benchmark-java/src/main/resources")
                );
                for (var path : fallbackPaths) {
                    if (Files.exists(path)) {
                        var fileUrl = path.toUri().toURL();
                        if (!urls.contains(fileUrl)) urls.add(fileUrl);
                    }
                }
            } catch (Exception ignored) {}
        }
        ClassLoader finalClassLoader = urls.isEmpty()
            ? processorClassLoader
            : new URLClassLoader(urls.toArray(new URL[0]), processorClassLoader);

        boolean isYaml = false;
        try {
            isYaml = finalClassLoader.getResource("application.yaml") != null
                || finalClassLoader.getResource("application.yml") != null
                || finalClassLoader.getResource("reference.yml") != null
                || finalClassLoader.getResource("reference.yaml") != null;
        } catch (Exception ignored) {}

        if (isYaml) {
            configParser = new YamlAotParser();
        } else {
            configParser = new HoconAotParser();
        }

        configParser.loadAndMerge(finalClassLoader, customResource, customFile);
    }

    @Override
    protected void process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv, Map<ClassName, List<AnnotatedElement>> annotatedElements) {
        var messager = processingEnv.getMessager();
        messager.printMessage(Kind.NOTE, "[AOT-Config] Processor invoked. Over: " + roundEnv.processingOver());

        var targetElements = annotatedElements.getOrDefault(ConfigClassNames.configSourceAnnotation, List.of());
        var localConfigs = new ArrayList<TypeElement>();

        messager.printMessage(Kind.NOTE, "[AOT-Config] Found target annotated elements size: " + targetElements.size());

        for (var annotated : targetElements) {
            if (annotated.element() instanceof TypeElement te) {
                messager.printMessage(Kind.NOTE, "[AOT-Config] Processing local class: " + te.getQualifiedName());
                localConfigs.add(te);
                processedConfigClasses.add(te.getQualifiedName().toString());
            }
        }

        if (!localConfigs.isEmpty()) {
            if (!isConfigLoaded) {
                var firstPackageElement = elements.getPackageOf(localConfigs.getFirst());
                loadAllConfigsAtCompileTime(firstPackageElement.getQualifiedName().toString());
            }
            for (var config : localConfigs) {
                messager.printMessage(Kind.NOTE, "[AOT-Config] Generating AOT module for: " + config.getSimpleName());
                generateAotModuleForConfig(config);
            }
            messager.printMessage(Kind.NOTE, "[AOT-Config] Writing meta-inf for local configs...");
            writeMetaInfConfigSources(localConfigs);
        }

        if (roundEnv.processingOver()) {
            messager.printMessage(Kind.NOTE, "[AOT-Config] Reached processingOver round. Reading external config sources...");
            var externalConfigClassNames = readExternalConfigSources();
            messager.printMessage(Kind.NOTE, "[AOT-Config] Found external config sources in classpath size: " + externalConfigClassNames.size());

            if (!externalConfigClassNames.isEmpty()) {
                if (!isConfigLoaded) {
                    loadAllConfigsAtCompileTime("io.koraframework.config.aot");
                }
                for (String className : externalConfigClassNames) {
                    if (processedConfigClasses.add(className)) {
                        messager.printMessage(Kind.NOTE, "[AOT-Config] Processing external class from SPI: " + className);
                        TypeElement te = processingEnv.getElementUtils().getTypeElement(className);
                        if (te != null) {
                            generateAotModuleForConfig(te);
                        } else {
                            messager.printMessage(Kind.WARNING, "[AOT-Config] Could not resolve TypeElement for external class: " + className);
                        }
                    }
                }
            }
        }
    }

    private void generateAotModuleForConfig(TypeElement config) {
        var resolversClass = ClassName.get("io.koraframework.config.common.aot", "AotConfigResolvers");
        var defaultComponentAnno = ClassName.get("io.koraframework.common.annotation", "DefaultComponent");

        var moduleName = config.getSimpleName() + "AotModule";
        var packageElement = elements.getPackageOf(config);
        var packageName = packageElement.getQualifiedName().toString();

        var typeBuilder = TypeSpec.interfaceBuilder(moduleName)
            .addOriginatingElement(config)
            .addAnnotation(AnnotationUtils.generated(AotConfigProcessor.class))
            .addAnnotation(CommonClassNames.module)
            .addModifiers(Modifier.PUBLIC);

        typeBuilder.addType(TypeSpec.classBuilder("Impl")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get(packageName, moduleName))
            .build());

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
            var pathElement = ClassName.get("io.koraframework.config.common", "PathElement");

            var configMapperType = ParameterizedTypeName.get(mapperInterface, TypeName.get(config.asType()));
            method.addParameter(configMapperType, "mapper");

            initCode.add("var origin = new $T(\n", simpleOrigin);
            initCode.add("  new $T(\"aot-compiled\"),\n", configOrigin);
            initCode.add("  $T.root()\n", valuePath);
            initCode.add(");\n\n");
            initCode.add("var rootMap = new $T<String, $T<?>>();\n", LinkedHashMap.class, ConfigValue.class);

            class ChunkContext {
                int totalFields = 0;
                int currentChunkMethodIndex = 0;
                CodeBlock.Builder currentChunkBuilder = CodeBlock.builder();
                final List<MethodSpec> chunkMethods = new ArrayList<>();
            }
            final ChunkContext ctx = new ChunkContext();

            final String currentMethodName = methodName;
            Consumer<CodeBlock> chunkAppender = (codeLine) -> {
                ctx.currentChunkBuilder.add(codeLine);
                ctx.totalFields++;

                if (ctx.totalFields % MAX_FIELDS_PER_CHUNK == 0) {
                    String chunkMethodName = currentMethodName + "_chunk" + ctx.currentChunkMethodIndex;
                    var chunkMethod = MethodSpec.methodBuilder(chunkMethodName)
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(
                            ParameterizedTypeName.get(
                                ClassName.get(Map.class),
                                ClassName.get(String.class),
                                ParameterizedTypeName.get(ClassName.get(ConfigValue.class), WildcardTypeName.subtypeOf(Object.class))
                            ),
                            "rootMap"
                        )
                        .addCode(ctx.currentChunkBuilder.build())
                        .build();
                    ctx.chunkMethods.add(chunkMethod);
                    initCode.add("$L(rootMap);\n", chunkMethodName);
                    ctx.currentChunkBuilder = CodeBlock.builder();
                    ctx.currentChunkMethodIndex++;
                }
            };

            generateFieldResolvers(chunkAppender, config, path, configParser, new HashSet<>());

            if (ctx.totalFields % MAX_FIELDS_PER_CHUNK != 0 || ctx.totalFields == 0) {
                String chunkMethodName = currentMethodName + "_chunk" + ctx.currentChunkMethodIndex;
                var chunkMethod = MethodSpec.methodBuilder(chunkMethodName)
                    .addModifiers(Modifier.PRIVATE)
                    .addParameter(
                        ParameterizedTypeName.get(
                            ClassName.get(Map.class),
                            ClassName.get(String.class),
                            ParameterizedTypeName.get(ClassName.get(ConfigValue.class), WildcardTypeName.subtypeOf(Object.class))
                        ),
                        "rootMap"
                    )
                    .addCode(ctx.currentChunkBuilder.build())
                    .build();
                ctx.chunkMethods.add(chunkMethod);
                initCode.add("$L(rootMap);\n", chunkMethodName);
            }

            initCode.add("\nvar finalObjectValue = new $T(origin, rootMap);\n", objectValue);

            if (path.isBlank()) {
                initCode.add("var targetValue = finalObjectValue;\n");
            } else {
                String[] pathParts = path.split("\\.");
                initCode.add("var targetValue = finalObjectValue");
                for (int i = 0; i < pathParts.length; i++) {
                    initCode.add(".get($T.get($S))", pathElement, pathParts[i]);
                    if (i < pathParts.length - 1) {
                        initCode.add(".asObject()");
                    }
                }
                initCode.add(";\n");
            }

            initCode.add("return mapper.map(targetValue);\n");
            method.addCode(initCode.build());
            typeBuilder.addMethod(method.build());
            for (MethodSpec chunk : ctx.chunkMethods) {
                typeBuilder.addMethod(chunk);
            }
        } else {
            typeBuilder.addMethod(method.build());
        }

        var javaFile = JavaFile.builder(packageName, typeBuilder.build())
            .addStaticImport(resolversClass, "*")
            .addStaticImport(ClassName.get(Map.class), "*")
            .build();

        CommonUtils.safeWriteTo(processingEnv, javaFile);
    }

    private void writeMetaInfConfigSources(List<TypeElement> elements) {
        if (elements.isEmpty()) return;
        var messager = processingEnv.getMessager();
        try {
            var resource = processingEnv.getFiler().createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                META_INF_PATH
            );
            messager.printMessage(Kind.NOTE, "[AOT-Config] Resource created at: " + resource.toUri());
            try (var writer = new BufferedWriter(new OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8))) {
                for (var element : elements) {
                    writer.write(element.getQualifiedName().toString());
                    writer.newLine();
                }
            }
            messager.printMessage(Kind.NOTE, "[AOT-Config] META-INF successfully written.");
        } catch (Exception e) {
            messager.printMessage(
                Kind.ERROR,
                "[AOT-Config] CRITICAL ERROR writing META-INF: " + e.getMessage() + " | Type: " + e.getClass().getName()
            );
        }
    }

    private Set<String> readExternalConfigSources() {
        var result = new LinkedHashSet<String>();
        try {
            var resources = getClass().getClassLoader().getResources(META_INF_PATH);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                try (var reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            result.add(line.trim());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void generateFieldResolvers(Consumer<CodeBlock> chunkAppender, TypeElement configElement, String currentPath, AotConfigParser parser, Set<TypeElement> visited) {
        if (configElement == null || !visited.add(configElement)) {
            return;
        }

        try {
            var configFields = ConfigUtils.parseFields(types, configElement).left();
            if (configFields == null) return;

            for (var field : configFields) {
                var fullKey = currentPath.isBlank() ? field.name() : currentPath + "." + field.name();
                var hasInterfaceDefault = field.hasDefault();
                var typeName = field.typeName();

                boolean isNestedInterface = false;
                TypeElement targetElement = null;

                if (typeName instanceof ClassName className && !isStandardType(className.toString())) {
                    targetElement = processingEnv.getElementUtils().getTypeElement(className.toString());
                    if (targetElement == null) {
                        String binaryName = className.packageName() + "." + String.join("$", className.simpleNames());
                        targetElement = processingEnv.getElementUtils().getTypeElement(binaryName);
                    }
                    if (targetElement != null && targetElement.getKind() == ElementKind.INTERFACE) {
                        isNestedInterface = true;
                    }
                }

                if (isNestedInterface) {
                    chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, null, null, false, null);\n", currentPath, field.name()));
                    generateFieldResolvers(chunkAppender, targetElement, fullKey, parser, visited);
                    continue;
                }

                var baseVal = parser.getConfigValue(fullKey);

                CodeBlock baseValBlock;
                CodeBlock fallbackBlock = CodeBlock.of("null");

                if (baseVal instanceof HoconAotParser.FallbackHolder holder) {
                    baseValBlock = CodeBlock.of("$S", holder.rawExpression);
                    fallbackBlock = convertToCodeBlock(holder.fallbackValue);
                } else {
                    baseValBlock = convertToCodeBlock(baseVal);
                }

                var relaxedVariants = PathElement.get(field.name()).relaxedNames();
                var listLiteral = CodeBlock.builder().add("$T.of(", List.class);
                for (int i = 0; i < relaxedVariants.size(); i++) {
                    listLiteral.add("$S", relaxedVariants.get(i));
                    if (i < relaxedVariants.size() - 1) listLiteral.add(", ");
                }
                listLiteral.add(")");

                chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, $L, $L, $L, $L);\n",
                    currentPath, field.name(), baseValBlock, fallbackBlock, hasInterfaceDefault, listLiteral.build()
                ));
            }
        } finally {
            visited.remove(configElement);
        }
    }

    private CodeBlock convertToCodeBlock(Object koraValue) {
        if (koraValue == null) return CodeBlock.of("null");
        if (koraValue instanceof String str) return CodeBlock.of("$S", str);
        if (koraValue instanceof Long lon) return CodeBlock.of("$LL", lon);
        if (koraValue instanceof Number num) return CodeBlock.of("$L", num);
        if (koraValue instanceof Boolean bool) return CodeBlock.of("$L", bool);
        if (koraValue instanceof List<?> list) {
            var builder = CodeBlock.builder().add("new Object[] { ");
            for (int i = 0; i < list.size(); i++) {
                builder.add(convertToCodeBlock(list.get(i)));
                if (i < list.size() - 1) builder.add(", ");
            }
            return builder.add(" }").build();
        }
        if (koraValue instanceof Map<?, ?> map) {
            var builder = CodeBlock.builder().add("$T.ofEntries(\n", Map.class);
            var entries = new ArrayList<>(map.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                builder.add("    $T.entry($S, $L)", Map.class, String.valueOf(entry.getKey()), convertToCodeBlock(entry.getValue()));
                if (i < entries.size() - 1) builder.add(",\n");
            }
            return builder.add("\n)").build();
        }
        return CodeBlock.of("$S", koraValue.toString());
    }

    private boolean isStandardType(String className) {
        return className.startsWith("java.lang.")
            || className.startsWith("java.time.")
            || className.startsWith("java.util.");
    }
}
