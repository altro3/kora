package io.koraframework.config.aot.processor;

import com.typesafe.config.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import io.koraframework.config.common.*;

import java.io.*;
import java.util.*;

public final class HoconAotParser implements AotConfigParser {

    private Config baseConfig;

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

        baseConfig = appConfig.withFallback(referenceConfig);
    }

    @Override
    public Object getConfigValue(String path) {
        if (baseConfig == null || path.isBlank()) return null;

        String[] parts = path.split("\\.");
        ConfigObject currentObject = baseConfig.root();
        ConfigValue targetValue = null;

        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];
            targetValue = currentObject.get(key);
            if (targetValue == null) {
                List<String> relaxedNames = PathElement.get(key).relaxedNames();
                for (String variant : relaxedNames) {
                    targetValue = currentObject.get(variant);
                    if (targetValue != null) break;
                }
            }
            if (targetValue == null) return null;
            if (i < parts.length - 1 && targetValue instanceof ConfigObject configObject) {
                currentObject = configObject;
            }
        }

        return extractUnresolvedValue(targetValue);
    }

    private Object extractUnresolvedValue(ConfigValue hoconValue) {
        if (hoconValue == null) return null;

        if (hoconValue instanceof ConfigObject obj) {
            var map = new LinkedHashMap<String, Object>(obj.size());
            for (var entry : obj.entrySet()) {
                map.put(entry.getKey(), extractUnresolvedValue(entry.getValue()));
            }
            return map;
        }

        if (hoconValue instanceof ConfigList list) {
            var res = new ArrayList<>(list.size());
            for (ConfigValue item : list) {
                res.add(extractUnresolvedValue(item));
            }
            return res;
        }

        String rendered = hoconValue.render(ConfigRenderOptions.concise().setJson(false));
        if (rendered != null && rendered.startsWith("\"") && rendered.endsWith("\"")) {
            rendered = rendered.substring(1, rendered.length() - 1);
        }

        if (rendered != null && rendered.contains("${")) {
            Object fallback = null;

            if (rendered.contains(",")) {
                String[] tokens = rendered.split(",");
                String firstToken = tokens[0].trim().replaceAll("^\"|\"$", "");

                if (firstToken.matches("-?\\d+")) {
                    fallback = Long.parseLong(firstToken);
                } else if (firstToken.matches("-?\\d+\\.\\d+")) {
                    fallback = Double.parseDouble(firstToken);
                } else if ("true".equalsIgnoreCase(firstToken) || "false".equalsIgnoreCase(firstToken)) {
                    fallback = Boolean.parseBoolean(firstToken);
                } else {
                    fallback = firstToken;
                }

                for (String token : tokens) {
                    if (token.contains("${")) {
                        rendered = token.trim();
                        break;
                    }
                }
            } else {
                try {
                    Config tempConfig = ConfigFactory.parseString("temp = " + hoconValue.render(ConfigRenderOptions.concise()))
                        .withFallback(baseConfig);
                    Config resolvedTemp = tempConfig.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
                    ConfigValue resolvedValue = resolvedTemp.getValue("temp");

                    if (!resolvedValue.render().contains("${")) {
                        fallback = resolvedValue.unwrapped();
                    }
                } catch (Exception ignored) {}
            }

            return new FallbackHolder(rendered, fallback);
        }

        try {
            return hoconValue.unwrapped();
        } catch (ConfigException.NotResolved notResolved) {
            return new FallbackHolder(rendered, null);
        }
    }

    public static final class FallbackHolder {
        public final String rawExpression;
        public final Object fallbackValue;

        public FallbackHolder(String rawExpression, Object fallbackValue) {
            this.rawExpression = rawExpression;
            this.fallbackValue = fallbackValue;
        }
    }
}
