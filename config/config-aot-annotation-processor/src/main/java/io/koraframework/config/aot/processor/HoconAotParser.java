package io.koraframework.config.aot.processor;

import com.typesafe.config.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import io.koraframework.config.common.*;

import java.io.*;
import java.util.*;

public final class HoconAotParser implements AotConfigParser {

    private static final ConfigRenderOptions CONCISE_NO_JSON = ConfigRenderOptions.concise().setJson(false);
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
                try {
                    if (targetValue.valueType() == ConfigValueType.OBJECT) {
                        currentObject = (ConfigObject) targetValue;
                    } else {
                        return null;
                    }
                } catch (ConfigException.NotResolved notResolved) {
                    return targetValue.render(CONCISE_NO_JSON);
                }
            }
        }

        try {
            if (targetValue.valueType() == ConfigValueType.NULL) {
                return null;
            }

            if (targetValue.valueType() == ConfigValueType.LIST) {
                return targetValue.render(ConfigRenderOptions.concise());
            }

            return targetValue.unwrapped();
        } catch (ConfigException.NotResolved notResolved) {
            return targetValue.render(CONCISE_NO_JSON);
        }
    }
}
