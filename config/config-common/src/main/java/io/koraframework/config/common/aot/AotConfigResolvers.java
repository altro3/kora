package io.koraframework.config.common.aot;

import io.koraframework.config.common.*;
import io.koraframework.config.common.impl.*;
import io.koraframework.config.common.origin.*;
import org.jspecify.annotations.*;

import java.util.*;

public final class AotConfigResolvers {

    private static final SimpleConfigOrigin AOT_ORIGIN = new SimpleConfigOrigin("aot-compiled");

    private AotConfigResolvers() {}

    public static void resolveAndPut(Map<String, ConfigValue<?>> rootMap, String configPath, String fieldName, @Nullable String baseValue, boolean hasInterfaceDefault) {
        String fullSysKey = configPath.isBlank() ? fieldName : configPath + "." + fieldName;

        var value = resolveValue(fullSysKey, baseValue, hasInterfaceDefault);
        if (value != null) {
            if (configPath.isBlank()) {
                rootMap.put(fieldName, value);
            } else {
                var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(configPath));

                rootMap.compute(configPath, (_, existing) -> {
                    Map<String, ConfigValue<?>> targetMap;
                    if (existing instanceof ConfigValue.ObjectValue oldObj) {
                        targetMap = new LinkedHashMap<>(oldObj.value());
                    } else {
                        targetMap = new LinkedHashMap<>();
                    }
                    targetMap.put(fieldName, value);
                    return new ConfigValue.ObjectValue(origin, targetMap);
                });
            }
        }
    }

    public static @Nullable ConfigValue<?> resolveValue(String sysKey, @Nullable String baseValue, boolean hasInterfaceDefault) {
        String lastPart = sysKey.contains(".") ? sysKey.substring(sysKey.lastIndexOf('.') + 1) : sysKey;
        String prefix = sysKey.contains(".") ? sysKey.substring(0, sysKey.lastIndexOf('.') + 1) : "";

        List<String> relaxedVariants = PathElement.get(lastPart).relaxedNames();

        for (String variant : relaxedVariants) {
            String fullKey = (prefix + variant).replace('.', '_').replace('-', '_').toUpperCase();
            var env = System.getenv(fullKey);
            if (env != null) return toConfigValue(sysKey, env);

            var sysEnvFallback = System.getProperty(fullKey);
            if (sysEnvFallback != null) return toConfigValue(sysKey, sysEnvFallback);
        }

        for (String variant : relaxedVariants) {
            String fullSysKey = prefix + variant;
            var sys = System.getProperty(fullSysKey);
            if (sys != null) return toConfigValue(sysKey, sys);
        }

        if (baseValue != null) return toConfigValue(sysKey, baseValue);

        if (hasInterfaceDefault) return null;

        return toConfigValue(sysKey, "");
    }

    private static ConfigValue.StringValue toConfigValue(String path, String value) {
        var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(path));
        return new ConfigValue.StringValue(origin, value);
    }
}
