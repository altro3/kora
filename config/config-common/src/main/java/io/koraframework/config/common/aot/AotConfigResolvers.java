package io.koraframework.config.common.aot;

import io.koraframework.config.common.exception.*;
import io.koraframework.config.common.origin.*;
import org.jspecify.annotations.*;

public final class AotConfigResolvers {

    private static @Nullable AotConfigResolvers INSTANCE = new AotConfigResolvers();
    private String @Nullable [] activeProfiles;

    private AotConfigResolvers() {}

    public static void clear() {
        INSTANCE = null;
    }

    private static AotConfigResolvers getInstance() {
        var current = INSTANCE;
        if (current == null) {
            return new AotConfigResolvers();
        }
        return current;
    }

    private String[] getActiveProfiles() {
        if (activeProfiles == null) {
            activeProfiles = System.getProperty("kora.profiles") != null
                ? System.getProperty("kora.profiles").split(",")
                : (System.getenv("KORA_PROFILES") != null ? System.getenv("KORA_PROFILES").split(",") : new String[0]);
        }
        return activeProfiles;
    }

    private @Nullable Object resolveRaw(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue) {
        var env = System.getenv(envKey);
        if (env != null) return env;
        var sys = System.getProperty(sysKey);
        if (sys != null) return sys;

        if (profileValues != null) {
            for (var active : getActiveProfiles()) {
                var trimmed = active.trim();
                for (int i = 0; i < profileValues.length; i += 2) {
                    if (trimmed.equals(profileValues[i])) return profileValues[i + 1];
                }
            }
        }
        return baseValue;
    }

    public static String resolveString(String envKey, String sysKey, @Nullable String defaultValue) {
        return resolveString(envKey, sysKey, null, null, defaultValue);
    }

    public static String resolveString(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, @Nullable String defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value != null) return value.toString();
        if (defaultValue != null) return defaultValue;
        throw new ConfigValueException(
            aotOrigin(sysKey),
            "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey)
        );
    }

    public static int resolveInt(String envKey, String sysKey, boolean hasDefault, int defaultValue) {
        return resolveInt(envKey, sysKey, null, null, hasDefault, defaultValue);
    }

    public static int resolveInt(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, int defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value instanceof Number n) return n.intValue();
        if (value != null) return Integer.parseInt(value.toString());
        if (hasDefault) return defaultValue;
        throw new ConfigValueException(
            aotOrigin(sysKey),
            "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey)
        );
    }

    public static boolean resolveBoolean(String envKey, String sysKey, boolean hasDefault, boolean defaultValue) {
        return resolveBoolean(envKey, sysKey, null, null, hasDefault, defaultValue);
    }

    public static boolean resolveBoolean(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, boolean defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        if (hasDefault) return defaultValue;
        throw new ConfigValueException(
            aotOrigin(sysKey),
            "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey)
        );
    }

    public static @Nullable Object resolveObject(String envKey, String sysKey, boolean hasDefault) {
        return resolveObject(envKey, sysKey, null, null, hasDefault);
    }

    public static @Nullable Object resolveObject(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value != null) return value;
        if (hasDefault) return null;
        throw new ConfigValueException(
            aotOrigin(sysKey),
            "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey)
        );
    }

    private static ConfigOrigin aotOrigin(String path) {
        return () -> "AOT Property: " + path;
    }
}
