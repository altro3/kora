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
        if (value == null) {
            return;
        }

        if (configPath.isBlank()) {
            rootMap.put(fieldName, value);
        } else {
            String[] pathParts = configPath.split("\\.");
            Map<String, ConfigValue<?>> currentMap = rootMap;
            SimpleConfigValueOrigin origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(pathParts[0]));

            for (int i = 0; i < pathParts.length; i++) {
                String part = pathParts[i];
                final boolean isLast = (i == pathParts.length - 1);

                ConfigValue<?> existing = currentMap.get(part);
                Map<String, ConfigValue<?>> targetMap;

                if (existing instanceof ConfigValue.ObjectValue oldObj) {
                    targetMap = new LinkedHashMap<>(oldObj.value());
                } else {
                    targetMap = new LinkedHashMap<>();
                }

                if (isLast) {
                    targetMap.put(fieldName, value);
                    currentMap.put(part, new ConfigValue.ObjectValue(origin, targetMap));
                } else {
                    ConfigValue.ObjectValue nextObj = new ConfigValue.ObjectValue(origin, targetMap);
                    currentMap.put(part, nextObj);
                    currentMap = targetMap;
                }
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

        if (baseValue != null) {
            String resolvedPlaceholder = tryResolvePlaceholder(baseValue);
            if (resolvedPlaceholder != null) {
                return toConfigValue(sysKey, resolvedPlaceholder);
            }
            return toConfigValue(sysKey, baseValue);
        }

        if (hasInterfaceDefault) return null;

        var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(sysKey));
        return new ConfigValue.ObjectValue(origin, new LinkedHashMap<>());
    }

    private static @Nullable String tryResolvePlaceholder(String value) {
        if (value == null || !value.contains("${")) {
            return null;
        }

        int start = value.indexOf("${");
        int end = value.indexOf("}", start);
        if (end == -1) {
            return null;
        }

        String content = value.substring(start + 2, end).trim();
        boolean optional = content.startsWith("?");
        if (optional) {
            content = content.substring(1).trim();
        }

        String varName = content;
        String fallback = null;
        if (content.contains(":")) {
            int colonIndex = content.indexOf(":");
            varName = content.substring(0, colonIndex).trim();
            fallback = content.substring(colonIndex + 1).trim();
        }

        var envVal = System.getenv(varName);
        if (envVal != null) return envVal;

        var sysVal = System.getProperty(varName);
        if (sysVal != null) return sysVal;

        if (fallback != null) return fallback;
        if (optional) return "";

        return null;
    }

    private static ConfigValue.StringValue toConfigValue(String path, String value) {
        var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(path));
        return new ConfigValue.StringValue(origin, value);
    }
}
