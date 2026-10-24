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
        referenceConfig = referenceConfig.resolve(ConfigResolveOptions.defaults());

        Config appConfig;
        if (customFile != null && !customFile.isBlank()) {
            appConfig = ConfigFactory.parseFile(new File(customFile), parseOptions);
        } else if (customResource != null && !customResource.isBlank()) {
            appConfig = ConfigFactory.parseResources(customResource, parseOptions);
        } else {
            appConfig = ConfigFactory.parseResources("application.conf", parseOptions);
        }

        Config merged = appConfig.withFallback(referenceConfig);
        baseConfig = merged.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
    }

    @Override
    public Object getConfigValue(String path) {
        if (baseConfig == null || path.isBlank()) {
            return null;
        }

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

        return extractUnresolvedValue(targetValue);
    }

    private Object extractUnresolvedValue(ConfigValue hoconValue) {
        if (hoconValue == null) {
            return null;
        }

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
        if (rendered != null && rendered.contains("${")) {
            if (rendered.startsWith("\"") && rendered.endsWith("\"")) {
                rendered = rendered.substring(1, rendered.length() - 1);
            }
            return rendered;
        }

        try {
            return hoconValue.unwrapped();
        } catch (ConfigException.NotResolved notResolved) {
            String fallbackRender = hoconValue.render(ConfigRenderOptions.concise().setJson(false));
            if (fallbackRender != null && fallbackRender.startsWith("\"") && fallbackRender.endsWith("\"")) {
                fallbackRender = fallbackRender.substring(1, fallbackRender.length() - 1);
            }
            return fallbackRender;
        }
    }
}
