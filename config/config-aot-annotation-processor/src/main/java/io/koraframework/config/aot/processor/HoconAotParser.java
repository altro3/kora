package io.koraframework.config.aot.processor;

import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.Map;

public final class HoconAotParser {
    public static Map<String, Object> parse(String mainContent, String profileContent) {
        var result = new HashMap<String, Object>();
        try {
            var mainConfig = ConfigFactory.parseString(mainContent);
            if (profileContent != null && !profileContent.isBlank()) {
                var profileConfig = ConfigFactory.parseString(profileContent);
                mainConfig = profileConfig.withFallback(mainConfig);
            }
            var resolved = mainConfig.resolve();
            for (var entry : resolved.entrySet()) {
                result.put(entry.getKey(), entry.getValue().unwrapped());
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
