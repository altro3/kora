package io.koraframework.config.common.aot;

import io.koraframework.config.common.*;
import io.koraframework.config.common.impl.*;
import io.koraframework.config.common.origin.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AotConfigResolvers {

    private static final SimpleConfigOrigin AOT_ORIGIN = new SimpleConfigOrigin("aot-compiled");
    private static final Pattern PLACEOHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private AotConfigResolvers() {}

    public static void resolveAndPut(Map<String, ConfigValue<?>> rootMap, String configPath, String fieldName, @Nullable Object baseValue, boolean hasInterfaceDefault) {
        String fullSysKey = configPath.isBlank() ? fieldName : configPath + "." + fieldName;

        var value = resolveValue(fullSysKey, baseValue, hasInterfaceDefault);
        if (value == null) {
            return;
        }

        if (configPath.isBlank()) {
            rootMap.put(fieldName, value);
        } else {
            String[] pathParts = configPath.split("\\.");
            var currentMap = rootMap;
            var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(pathParts[0]));

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

    public static @Nullable ConfigValue<?> resolveValue(String sysKey, @Nullable Object baseValue, boolean hasInterfaceDefault) {
        String lastPart = sysKey.contains(".") ? sysKey.substring(sysKey.lastIndexOf('.') + 1) : sysKey;
        String prefix = sysKey.contains(".") ? sysKey.substring(0, sysKey.lastIndexOf('.') + 1) : "";

        List<String> relaxedVariants = PathElement.get(lastPart).relaxedNames();

        for (String variant : relaxedVariants) {
            String fullKey = (prefix + variant).replace('.', '_').replace('-', '_').toUpperCase();
            var env = System.getenv(fullKey);
            if (env != null) return toKoraValue(sysKey, env);

            var sysEnvFallback = System.getProperty(fullKey);
            if (sysEnvFallback != null) return toKoraValue(sysKey, sysEnvFallback);
        }

        for (String variant : relaxedVariants) {
            String fullSysKey = prefix + variant;
            var sys = System.getProperty(fullSysKey);
            if (sys != null) return toKoraValue(sysKey, sys);
        }

        if (baseValue != null) {
            return toKoraValue(sysKey, baseValue);
        }

        if (hasInterfaceDefault) return null;

        var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(sysKey));
        return new ConfigValue.ObjectValue(origin, new LinkedHashMap<>());
    }

    private static ConfigValue<?> toKoraValue(String path, Object value) {
        var origin = new SimpleConfigValueOrigin(AOT_ORIGIN, ConfigValuePath.root().child(path));

        if (value instanceof String str) {
            if (str.contains("${")) {
                String resolved = tryResolveInterpolatedPlaceholders(str);
                return new ConfigValue.StringValue(origin, resolved != null ? resolved : str);
            }
            return new ConfigValue.StringValue(origin, str);
        }
        if (value instanceof Number num) {
            return new ConfigValue.NumberValue(origin, num);
        }
        if (value instanceof Boolean bool) {
            return new ConfigValue.BooleanValue(origin, bool);
        }
        if (value instanceof Map<?, ?> map) {
            var res = new LinkedHashMap<String, ConfigValue<?>>(map.size());
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                res.put(key, toKoraValue(path + "." + key, entry.getValue()));
            }
            return new ConfigValue.ObjectValue(origin, res);
        }
        if (value instanceof Object[] array) {
            var res = new ArrayList<ConfigValue<?>>(array.length);
            for (int i = 0; i < array.length; i++) {
                res.add(toKoraValue(path + "[" + i + "]", array[i]));
            }
            return new ConfigValue.ArrayValue(origin, List.copyOf(res));
        }
        return new ConfigValue.StringValue(origin, String.valueOf(value));
    }

    private static @Nullable String tryResolveInterpolatedPlaceholders(String value) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = PLACEOHOLDER_PATTERN.matcher(value);
        int lastAppendPosition = 0;
        boolean hasUnresolvedRequired = false;

        while (matcher.find()) {
            sb.append(value, lastAppendPosition, matcher.start());
            String content = matcher.group(1).trim();

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

            String resolved = System.getenv(varName);
            if (resolved == null) {
                resolved = System.getProperty(varName);
            }
            if (resolved == null && fallback != null) {
                resolved = fallback;
            }

            if (resolved != null) {
                sb.append(resolved);
            } else if (!optional) {
                hasUnresolvedRequired = true;
                sb.append(matcher.group(0));
            }
            lastAppendPosition = matcher.end();
        }

        sb.append(value, lastAppendPosition, value.length());
        return hasUnresolvedRequired ? null : sb.toString();
    }
}
