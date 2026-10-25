package io.koraframework.config.aot.processor;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.*;
import io.koraframework.config.common.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class AotConfigProcessor extends AbstractKoraProcessor {

    private static final int MAX_FIELDS_PER_CHUNK = 400;

    private AotConfigParser configParser = null;
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

        String customResource = processingEnv.getOptions().get("config.resource");
        if (customResource == null) customResource = System.getProperty("config.resource");

        String customFile = processingEnv.getOptions().get("config.file");
        if (customFile == null) customFile = System.getProperty("config.file");

        var urls = new ArrayList<URL>();

        ClassLoader processorClassLoader = getClass().getClassLoader();
        try {
            List<String> filesToSearch = List.of("reference.conf", "application.yaml", "application.yml");
            for (String fileName : filesToSearch) {
                Enumeration<URL> resources = processorClassLoader.getResources(fileName);
                while (resources.hasMoreElements()) {
                    URL resourceUrl = resources.nextElement();
                    urls.add(resourceUrl);
                }
            }
        } catch (Exception ignored) {}

        try {
            var runDir = Path.of(System.getProperty("user.dir", "."));
            String packagePath = packageName.replace('.', '/');

            var searchPaths = List.of(
                runDir.resolve("build/in-test-generated/classes").resolve(packagePath),
                runDir.resolve("build/in-test-generated/sources").resolve(packagePath),
                runDir.resolve("src/main/resources")
            );

            for (var path : searchPaths) {
                if (Files.exists(path)) {
                    var fileUrl = path.toUri().toURL();
                    if (!urls.contains(fileUrl)) {
                        urls.add(fileUrl);
                    }
                }
            }
        } catch (Exception ignored) {}

        ClassLoader finalClassLoader = urls.isEmpty()
            ? processorClassLoader
            : new URLClassLoader(urls.toArray(new URL[0]), processorClassLoader);

        boolean isYaml = false;
        try {
            isYaml = finalClassLoader.getResource("application.yaml") != null
                || finalClassLoader.getResource("application.yml") != null;
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
            var firstConfig = (TypeElement) targetElements.getFirst().element();
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
                                    ParameterizedTypeName.get(configValue, WildcardTypeName.subtypeOf(Object.class))
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

                generateFieldResolvers(chunkAppender, config, path, configParser);

                if (ctx.totalFields % MAX_FIELDS_PER_CHUNK != 0 || ctx.totalFields == 0) {
                    String chunkMethodName = currentMethodName + "_chunk" + ctx.currentChunkMethodIndex;
                    var chunkMethod = MethodSpec.methodBuilder(chunkMethodName)
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(
                            ParameterizedTypeName.get(
                                ClassName.get(Map.class),
                                ClassName.get(String.class),
                                ParameterizedTypeName.get(configValue, WildcardTypeName.subtypeOf(Object.class))
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
                    initCode.add("var targetValue = finalObjectValue.get($T.get($S));\n", pathElement, path);
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
                .build();

            CommonUtils.safeWriteTo(processingEnv, javaFile);
        }
    }

    private void generateFieldResolvers(Consumer<CodeBlock> chunkAppender, TypeElement configElement, String currentPath, AotConfigParser parser) {
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
                chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, null, null, false);\n", currentPath, field.name()));
                generateFieldResolvers(chunkAppender, targetElement, fullKey, parser);
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

            chunkAppender.accept(CodeBlock.of("resolveAndPut(rootMap, $S, $S, $L, $L, $L);\n",
                currentPath, field.name(), baseValBlock, fallbackBlock, hasInterfaceDefault
            ));
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
