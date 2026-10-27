package io.koraframework.config.aot.gradle;

import io.github.classgraph.*;
import io.koraframework.config.aot.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
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
            logger.lifecycle("[Kora-Aot] Found " + targetClasses.size() + " configuration classes to process.");

            try (var projectClassLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
                var format = configFormat;
                var file = configFile;

                if (format == null || format.isBlank()) {
                    if (file != null && !file.isBlank()) {
                        var lower = file.toLowerCase();
                        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {format = "YAML";} else if (lower.endsWith(".conf") || lower.endsWith(".json")) format = "HOCON";
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

                generateMonolithicAotModule(configParser, outputDir);
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

    private void generateMonolithicAotModule(AotConfigParser parser, File outputDir) throws Exception {
        var packageName = "io.koraframework.config.aot.generated";
        var moduleName = "AotConfigModule";

        var globalFlattenedValues = new LinkedHashMap<String, Object>();
        collectFlattenedProperties(parser, "", globalFlattenedValues);

        var fillDataBuilder = new StringBuilder();
        var chunksBuilder = new StringBuilder();

        if (!globalFlattenedValues.isEmpty()) {
            var entries = new ArrayList<>(globalFlattenedValues.entrySet());
            var currentChunkIndex = 0;
            var currentChunkBuilder = new StringBuilder();

            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                var fullKey = entry.getKey();
                var baseVal = entry.getValue();

                String statement = switch (baseVal) {
                    case null -> "null";
                    case Boolean b -> b.toString();
                    case Long l -> l + "L";
                    case Number n -> n.toString();
                    case String s -> "\"" + s.replace("\"", "\\\"") + "\"";
                    case List<?> list -> {
                        var listBuilder = new StringBuilder("java.util.List.of(");
                        for (var j = 0; j < list.size(); j++) {
                            var item = list.get(j);
                            if (item instanceof String) listBuilder.append("\"").append(item.toString().replace("\"", "\\\"")).append("\"");
                            else if (item instanceof Long) listBuilder.append(item).append("L");
                            else listBuilder.append(item);
                            if (j < list.size() - 1) listBuilder.append(", ");
                        }
                        yield listBuilder.append(")").toString();
                    }
                    default -> "\"" + baseVal.toString().replace("\"", "\\\"") + "\"";
                };

                var pathBuilder = new StringBuilder("r");
                int start = 0;
                int dotIdx;
                while ((dotIdx = fullKey.indexOf('.', start)) != -1) {
                    pathBuilder.append(".child(\"").append(fullKey.substring(start, dotIdx)).append("\")");
                    start = dotIdx + 1;
                }
                pathBuilder.append(".child(\"").append(fullKey.substring(start)).append("\")");

                currentChunkBuilder.append("            root.put(\"").append(fullKey).append("\", new AotLeaf(").append(pathBuilder).append(", ").append(statement).append("));\n");

                if ((i + 1) % MAX_FIELDS_PER_CHUNK == 0 || (i + 1) == entries.size()) {
                    var chunkMethodName = "initChunk" + currentChunkIndex;

                    chunksBuilder.append("        private static void ").append(chunkMethodName).append("(Map<String, Object> root) {\n")
                        .append("            var r = ConfigValuePath.root();\n")
                        .append(currentChunkBuilder)
                        .append("        }\n\n");

                    fillDataBuilder.append("            ").append(chunkMethodName).append("(root);\n");

                    currentChunkBuilder = new StringBuilder();
                    currentChunkIndex++;
                }
            }
        }

        String template;
        try (var is = getClass().getResourceAsStream("/templates/AotConfigModule.java")) {
            if (is == null) throw new FileNotFoundException("Template not found in plugin resources");
            template = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        var finalCode = template
            .replace("// TARGET_INJECTION_POINT", fillDataBuilder.toString().trim())
            .replace("// CHUNKS_INJECTION_POINT", chunksBuilder.toString().trim());

        var targetSubDir = outputDir.toPath().resolve(packageName.replace('.', '/'));
        Files.createDirectories(targetSubDir);
        Files.writeString(targetSubDir.resolve(moduleName + ".java"), finalCode);
    }

    private void collectFlattenedProperties(AotConfigParser parser, String currentPath, Map<String, Object> result) {
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
                var hasComplexStructures = false;
                for (var item : list) {
                    if (item instanceof Map || item instanceof List) {
                        hasComplexStructures = true;
                        break;
                    }
                }

                if (hasComplexStructures) {
                    for (var i = 0; i < list.size(); i++) {
                        collectFlattenedProperties(parser, currentPath + "." + i, result);
                    }
                } else {
                    result.put(currentPath, list);
                }
            }
            case HoconAotParser.FallbackHolder holder -> {
                if (holder.fallbackValue != null) result.put(currentPath, holder.fallbackValue);
            }
            default -> result.put(currentPath, baseVal);
        }
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            var files = file.listFiles();
            if (files != null) {
                for (var f : files) {delete(f);}
            }
        }
        file.delete();
    }

    public interface AppLogger {
        void lifecycle(String message);
    }
}
