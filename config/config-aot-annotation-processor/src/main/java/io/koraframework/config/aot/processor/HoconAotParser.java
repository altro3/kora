package io.koraframework.config.aot.processor;

import com.typesafe.config.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import io.koraframework.config.common.*;

import java.io.*;
import java.util.*;

public final class HoconAotParser implements AotConfigParser {

    private Config resolvedConfig;
    private final Map<String, String> optionalPlaceholders = new HashMap<>();

    @Override
    public void loadAndMerge(ClassLoader classLoader, String customResource, String customFile) {
        ConfigParseOptions parseOptions = ConfigParseOptions.defaults().setClassLoader(classLoader);

        Config referenceConfig = ConfigFactory.parseResources("reference.conf", parseOptions);

        Config appConfig;
        if (customFile != null && !customFile.isBlank()) {
            appConfig = ConfigFactory.parseFile(new File(customFile), parseOptions);
        } else if (customResource != null && !customResource.isBlank()) {
            appConfig = ConfigFactory.parseResources(customResource, parseOptions);
        } else {
            appConfig = ConfigFactory.parseResources("application.conf", parseOptions);
        }

        Config merged = appConfig.withFallback(referenceConfig);

        collectOptionalPlaceholders("", merged.root());

        resolvedConfig = merged.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
    }

    private void collectOptionalPlaceholders(String currentPath, ConfigValue value) {
        if (value instanceof ConfigObject obj) {
            for (var entry : obj.entrySet()) {
                String nextPath = currentPath.isBlank() ? entry.getKey() : currentPath + "." + entry.getKey();
                collectOptionalPlaceholders(nextPath, entry.getValue());
            }
        } else if (value instanceof ConfigList list) {
            for (int i = 0; i < list.size(); i++) {
                String nextPath = currentPath + "[" + i + "]";
                collectOptionalPlaceholders(nextPath, list.get(i));
            }
        } else {
            String rawValue = value.render(ConfigRenderOptions.concise().setJson(false));
            if (rawValue != null && rawValue.contains("${?")) {
                if (rawValue.contains(",") && !rawValue.startsWith("${")) {
                    return;
                }

                if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                    rawValue = rawValue.substring(1, rawValue.length() - 1);
                }
                optionalPlaceholders.put(currentPath, rawValue);
            }
        }
    }

    @Override
    public Object getConfigValue(String path) {
        if (resolvedConfig == null || path.isBlank()) {
            return null;
        }

        // Если этот путь изначально содержал опциональный плейсхолдер — отдаем сохраненный сырой оригинал
        if (optionalPlaceholders.containsKey(path)) {
            return optionalPlaceholders.get(path);
        }

        String[] parts = path.split("\\.");
        ConfigObject currentObject = resolvedConfig.root();
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
                if (targetValue instanceof ConfigObject configObject) {
                    currentObject = configObject;
                } else {
                    return null;
                }
            }
        }

        return extractUnresolvedValue(targetValue, path);
    }

    private Object extractUnresolvedValue(ConfigValue hoconValue, String currentPath) {
        if (hoconValue == null) {
            return null;
        }

        if (hoconValue instanceof ConfigObject obj) {
            var map = new LinkedHashMap<String, Object>(obj.size());
            for (var entry : obj.entrySet()) {
                String nextPath = currentPath.isBlank() ? entry.getKey() : currentPath + "." + entry.getKey();
                if (optionalPlaceholders.containsKey(nextPath)) {
                    map.put(entry.getKey(), optionalPlaceholders.get(nextPath));
                } else {
                    map.put(entry.getKey(), extractUnresolvedValue(entry.getValue(), nextPath));
                }
            }
            return map;
        }

        if (hoconValue instanceof ConfigList list) {
            var res = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String nextPath = currentPath + "[" + i + "]";
                if (optionalPlaceholders.containsKey(nextPath)) {
                    res.add(optionalPlaceholders.get(nextPath));
                } else {
                    res.add(extractUnresolvedValue(list.get(i), nextPath));
                }
            }
            return res;
        }

        try {
            return hoconValue.unwrapped();
        } catch (ConfigException.NotResolved notResolved) {
            String rendered = hoconValue.render(ConfigRenderOptions.concise().setJson(false));
            if (rendered != null && rendered.startsWith("\"") && rendered.endsWith("\"")) {
                rendered = rendered.substring(1, rendered.length() - 1);
            }
            return rendered;
        }
    }
}
