package io.koraframework.config.aot.processor;

import com.typesafe.config.*;
import java.util.*;

public final class HoconAotParser {
    private static final ConfigRenderOptions CONCISE_NO_JSON = ConfigRenderOptions.concise().setJson(false);

    public static Map<String, Object> parse(String mainContent, String profileContent) {
        try {
            var mainConfig = ConfigFactory.parseString(mainContent);
            if (profileContent != null && !profileContent.isBlank()) {
                var profileConfig = ConfigFactory.parseString(profileContent);
                mainConfig = profileConfig.withFallback(mainConfig);
            }
            var resolved = mainConfig.resolve(ConfigResolveOptions.defaults()
                .setAllowUnresolved(true));
            return toAotMap(resolved.root());
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, Object> toAotMap(ConfigObject object) {
        var result = new LinkedHashMap<String, Object>(object.size());
        for (var entry : object.entrySet()) {
            var value = toAotValue(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static Object toAotValue(ConfigValue configValue) {
        try {
            return switch (configValue.valueType()) {
                case OBJECT -> toAotMap((ConfigObject) configValue);
                case LIST -> toAotList((ConfigList) configValue);
                case NUMBER, BOOLEAN, STRING -> configValue.unwrapped();
                case NULL -> null;
            };
        } catch (ConfigException.NotResolved e) {
            return configValue.render(CONCISE_NO_JSON);
        }
    }

    private static List<Object> toAotList(ConfigList list) {
        var result = new ArrayList<>(list.size());
        for (var value : list) {
            result.add(toAotValue(value));
        }
        return List.copyOf(result);
    }
}
