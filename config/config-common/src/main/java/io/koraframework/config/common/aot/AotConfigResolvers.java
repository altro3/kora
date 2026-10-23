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

        var sysEnvFallback = System.getProperty(envKey);
        if (sysEnvFallback != null) return sysEnvFallback;

        var cleanEnvKey = envKey.replace("_", "");
        var envWithNoUnderscores = System.getenv(cleanEnvKey);
        if (envWithNoUnderscores != null) return envWithNoUnderscores;

        var sysEnvNoUnderscores = System.getProperty(cleanEnvKey);
        if (sysEnvNoUnderscores != null) return sysEnvNoUnderscores;

        var sys = System.getProperty(sysKey);
        if (sys != null) return sys;

        if (profileValues != null) {
            var profiles = getActiveProfiles();
            for (int i = profiles.length - 1; i >= 0; i--) {
                var trimmed = profiles[i].trim();
                for (int j = 0; j < profileValues.length; j += 2) {
                    if (trimmed.equals(profileValues[j])) return profileValues[j + 1];
                }
            }
        }
        return baseValue;
    }

    public static String resolveString(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable String defaultValue) {
        var result = resolveNullableString(envKey, sysKey, profileValues, baseValue, hasDefault, defaultValue);
        if (result == null) {
            throw new ConfigValueException(aotOrigin(sysKey), "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey));
        }
        return result;
    }

    public static @Nullable String resolveNullableString(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable String defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value != null) return value.toString();
        if (hasDefault) return defaultValue;
        return null;
    }

    public static int resolveInt(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Integer defaultValue) {
        var result = resolveNullableInt(envKey, sysKey, profileValues, baseValue, hasDefault, defaultValue);
        if (result == null) {
            throw new ConfigValueException(aotOrigin(sysKey), "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey));
        }
        return result;
    }

    public static @Nullable Integer resolveNullableInt(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Integer defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value instanceof Number n) return n.intValue();
        if (value != null) return Integer.parseInt(value.toString());
        if (hasDefault) return defaultValue;
        return null;
    }

    public static boolean resolveBoolean(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Boolean defaultValue) {
        var result = resolveNullableBoolean(envKey, sysKey, profileValues, baseValue, hasDefault, defaultValue);
        if (result == null) {
            throw new ConfigValueException(aotOrigin(sysKey), "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey));
        }
        return result;
    }

    public static @Nullable Boolean resolveNullableBoolean(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Boolean defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        if (hasDefault) return defaultValue;
        return null;
    }

    public static Object resolveObject(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Object defaultValue) {
        var result = resolveNullableObject(envKey, sysKey, profileValues, baseValue, hasDefault, defaultValue);
        if (result == null) {
            throw new ConfigValueException(aotOrigin(sysKey), "Required configuration property is missing: '%s' (ENV: '%s')".formatted(sysKey, envKey));
        }
        return result;
    }

    public static @Nullable Object resolveNullableObject(String envKey, String sysKey, Object @Nullable [] profileValues, @Nullable Object baseValue, boolean hasDefault, @Nullable Object defaultValue) {
        var value = getInstance().resolveRaw(envKey, sysKey, profileValues, baseValue);
        if (value != null) return value;
        if (hasDefault) return defaultValue;
        return null;
    }

    private static ConfigOrigin aotOrigin(String path) {
        return () -> "AOT Property: " + path;
    }
}
