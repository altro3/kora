package io.koraframework.config.common.aot;

import io.koraframework.config.common.*;
import io.koraframework.config.common.impl.*;
import io.koraframework.config.common.origin.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.regex.*;

public final class AotConfigResolvers {

    private static volatile ResolverState state = new ResolverState();

    private AotConfigResolvers() {}

    public static void clear() {
        state = null;
    }

    private static ResolverState getState() {
        ResolverState current = state;
        if (current == null) {
            return new ResolverState();
        }
        return current;
    }

    public static void resolveAndPut(Map<String, ConfigValue<?>> rootMap, String configPath, String fieldName, @Nullable Object baseValue, @Nullable Object fallbackValue, boolean hasInterfaceDefault) {
        String fullSysKey = configPath.isBlank() ? fieldName : configPath + "." + fieldName;

        var value = resolveValue(rootMap, fullSysKey, baseValue, fallbackValue, hasInterfaceDefault);
        if (value == null) {
            return;
        }

        if (configPath.isBlank()) {
            rootMap.put(fieldName, value);
            return;
        }

        ResolverState currentState = getState();
        String[] pathParts = currentState.pathSplit.split(configPath);

        var mapsChain = new ArrayList<Map<String, ConfigValue<?>>>();
        var partsChain = new ArrayList<String>();
        var originsChain = new ArrayList<SimpleConfigValueOrigin>();

        Map<String, ConfigValue<?>> currentMap = rootMap;
        ConfigValuePath currentPath = ConfigValuePath.root();

        for (var i = 0; i < pathParts.length; i++) {
            String part = pathParts[i];
            currentPath = currentPath.child(part);
            var origin = new SimpleConfigValueOrigin(currentState.aotOrigin, currentPath);

            ConfigValue<?> existing = currentMap.get(part);
            Map<String, ConfigValue<?>> targetMap;
            if (existing instanceof ConfigValue.ObjectValue oldObj) {
                targetMap = new LinkedHashMap<>(oldObj.value());
            } else {
                targetMap = new LinkedHashMap<>();
            }

            mapsChain.add(currentMap);
            partsChain.add(part);
            originsChain.add(origin);

            currentMap = targetMap;
        }

        currentMap.put(fieldName, value);

        for (int i = pathParts.length - 1; i >= 0; i--) {
            Map<String, ConfigValue<?>> parentMap = mapsChain.get(i);
            String part = partsChain.get(i);
            SimpleConfigValueOrigin origin = originsChain.get(i);

            parentMap.put(part, new ConfigValue.ObjectValue(origin, currentMap));
            currentMap = parentMap;
        }
    }

    public static @Nullable ConfigValue<?> resolveValue(Map<String, ConfigValue<?>> rootMap, String sysKey, @Nullable Object baseValue, @Nullable Object fallbackValue, boolean hasInterfaceDefault) {
        String lastPart = sysKey.contains(".") ? sysKey.substring(sysKey.lastIndexOf('.') + 1) : sysKey;
        String prefix = sysKey.contains(".") ? sysKey.substring(0, sysKey.lastIndexOf('.') + 1) : "";

        List<String> relaxedVariants = PathElement.get(lastPart).relaxedNames();

        for (String variant : relaxedVariants) {
            String fullKey = (prefix + variant).replace('.', '_').replace('-', '_').toUpperCase();
            var env = System.getenv(fullKey);
            if (env != null) return toKoraValue(rootMap, sysKey, env, fallbackValue);

            var sysEnvFallback = System.getProperty(fullKey);
            if (sysEnvFallback != null) return toKoraValue(rootMap, sysKey, sysEnvFallback, fallbackValue);
        }

        for (String variant : relaxedVariants) {
            String fullSysKey = prefix + variant;
            var sys = System.getProperty(fullSysKey);
            if (sys != null) return toKoraValue(rootMap, sysKey, sys, fallbackValue);
        }

        if (baseValue != null) {
            return toKoraValue(rootMap, sysKey, baseValue, fallbackValue);
        }

        if (hasInterfaceDefault) return null;

        if (fallbackValue != null) {
            return toKoraValue(rootMap, sysKey, fallbackValue, null);
        }

        ResolverState currentState = getState();
        var origin = new SimpleConfigValueOrigin(currentState.aotOrigin, ConfigValuePath.root().child(sysKey));
        return new ConfigValue.ObjectValue(origin, new LinkedHashMap<>());
    }

    private static ConfigValue<?> toKoraValue(Map<String, ConfigValue<?>> rootMap, String path, Object value, Object fallbackValue) {
        ResolverState currentState = getState();
        var origin = new SimpleConfigValueOrigin(currentState.aotOrigin, ConfigValuePath.root().child(path));

        if (value instanceof ConfigValue<?>) {
            return (ConfigValue<?>) value;
        }
        if (value instanceof String str) {
            if (str.contains("${")) {
                String resolved = tryResolveInterpolatedPlaceholders(rootMap, str);
                if (resolved != null) {
                    return new ConfigValue.StringValue(origin, resolved);
                }

                if (fallbackValue != null) {
                    return new ConfigValue.StringValue(origin, String.valueOf(fallbackValue));
                }

                if (str.contains("${?")) {
                    if (currentState.singleOptionalMatch.matcher(str.trim()).matches()) {
                        return new ConfigValue.ObjectValue(origin, new LinkedHashMap<>());
                    }

                    String cleanOptionalStr = currentState.cleanOptionalRegex.matcher(str).replaceAll("");
                    if (cleanOptionalStr.trim().isEmpty()) {
                        return new ConfigValue.ObjectValue(origin, new LinkedHashMap<>());
                    }
                }

                var missingVars = new ArrayList<String>();
                Matcher m = currentState.placeholder.matcher(str);
                while (m.find()) {
                    String varName = m.group(1).trim();
                    if (varName.contains(":")) {
                        varName = varName.substring(0, varName.indexOf(":")).trim();
                    }
                    if (varName.startsWith("?")) {
                        varName = varName.substring(1).trim();
                    }
                    missingVars.add(varName);
                }

                String varsString = missingVars.isEmpty() ? str : String.join(", ", missingVars);
                String customMessage = String.format(
                    "Config value parsing failed: missing required variable(s) [%s] at path '%s' for origin '%s'",
                    varsString,
                    path,
                    currentState.aotOrigin.description()
                );

                throw new io.koraframework.config.common.exception.ConfigValueException(origin.config(), customMessage);
            }
            return new ConfigValue.StringValue(origin, str);
        }
        if (value instanceof Number num) return new ConfigValue.NumberValue(origin, num);
        if (value instanceof Boolean bool) return new ConfigValue.BooleanValue(origin, bool);
        if (value instanceof Map<?, ?> map) {
            var res = new LinkedHashMap<String, ConfigValue<?>>(map.size());
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                res.put(key, toKoraValue(rootMap, path + "." + key, entry.getValue(), null));
            }
            return new ConfigValue.ObjectValue(origin, res);
        }
        if (value instanceof Object[] array) {
            var res = new ArrayList<ConfigValue<?>>(array.length);
            for (int i = 0; i < array.length; i++) {
                res.add(toKoraValue(rootMap, path + "[" + i + "]", array[i], null));
            }
            return new ConfigValue.ArrayValue(origin, List.copyOf(res));
        }
        if (value instanceof List<?> list) {
            var res = new ArrayList<ConfigValue<?>>(list.size());
            for (int i = 0; i < list.size(); i++) {
                res.add(toKoraValue(rootMap, path + "[" + i + "]", list.get(i), null));
            }
            return new ConfigValue.ArrayValue(origin, List.copyOf(res));
        }
        return new ConfigValue.StringValue(origin, String.valueOf(value));
    }

    private static @Nullable String tryResolveInterpolatedPlaceholders(Map<String, ConfigValue<?>> rootMap, String value) {
        String trimmed = value.trim();
        ResolverState currentState = getState();

        if (currentState.singleOptionalMatch.matcher(trimmed).matches()) {
            Matcher m = currentState.singleOptionalExtract.matcher(trimmed);
            if (m.find()) {
                String varName = m.group(1).trim();
                if (varName.contains(":")) {
                    varName = varName.substring(0, varName.indexOf(":")).trim();
                }
                String resolved = System.getenv(varName);
                if (resolved == null) {
                    String sysKey = varName.replace('_', '.').toLowerCase();
                    resolved = System.getProperty(varName);
                    if (resolved == null) resolved = System.getProperty(sysKey);
                }
                if (resolved == null) {
                    resolved = findInRootMap(rootMap, varName);
                }
                return resolved;
            }
        }

        var sb = new StringBuilder();
        Matcher matcher = currentState.placeholder.matcher(value);
        int lastAppendPosition = 0;
        boolean hasUnresolvedRequired = false;

        while (matcher.find()) {
            sb.append(value, lastAppendPosition, matcher.start());
            String content = matcher.group(1).trim();

            boolean optional = content.startsWith("?");
            if (optional) content = content.substring(1).trim();

            String varName = content;
            String fallbackToken = null;
            if (content.contains(":")) {
                int colonIndex = content.indexOf(":");
                varName = content.substring(0, colonIndex).trim();
                fallbackToken = content.substring(colonIndex + 1).trim();
            }

            String resolved = System.getenv(varName);
            if (resolved == null) {
                String sysKey = varName.replace('_', '.').toLowerCase();
                resolved = System.getProperty(varName);
                if (resolved == null) resolved = System.getProperty(sysKey);
            }

            if (resolved == null) {
                resolved = findInRootMap(rootMap, varName);
            }

            if (resolved == null && fallbackToken != null) {
                resolved = fallbackToken;
            }

            if (resolved != null) {
                sb.append(resolved);
            } else if (optional) {
                sb.append("");
            } else {
                hasUnresolvedRequired = true;
                sb.append(matcher.group(0));
            }
            lastAppendPosition = matcher.end();
        }

        sb.append(value, lastAppendPosition, value.length());

        return hasUnresolvedRequired ? null : sb.toString();
    }

    private static @Nullable String findInRootMap(Map<String, ConfigValue<?>> rootMap, String path) {
        ResolverState currentState = getState();
        String[] parts = currentState.pathSplit.split(path);
        Map<String, ConfigValue<?>> currentMap = rootMap;

        for (int i = 0; i < parts.length; i++) {
            ConfigValue<?> val = currentMap.get(parts[i]);
            if (val == null) {
                return null;
            }

            if (i == parts.length - 1) {
                if (val instanceof ConfigValue.StringValue
                    || val instanceof ConfigValue.NumberValue
                    || val instanceof ConfigValue.BooleanValue) {
                    return val.value() != null ? String.valueOf(val.value()) : null;
                }
                return null;
            }

            if (val instanceof ConfigValue.ObjectValue obj) {
                var nextMap = obj.value();
                if (nextMap == null) {
                    return null;
                }
                currentMap = nextMap;
            } else {
                return null;
            }
        }
        return null;
    }

    private static class ResolverState {
        final SimpleConfigOrigin aotOrigin = new SimpleConfigOrigin("aot-compiled");
        final Pattern pathSplit = Pattern.compile("\\.");
        final Pattern placeholder = Pattern.compile("\\$\\{([^}]+)}");
        final Pattern singleOptionalMatch = Pattern.compile("^\\$\\{\\?[^}]+}$");
        final Pattern singleOptionalExtract = Pattern.compile("\\$\\{\\?([^}]+)}");
        final Pattern cleanOptionalRegex = Pattern.compile("\\$\\{\\?[^}]+}");
    }
}
