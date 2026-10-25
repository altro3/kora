package io.koraframework.config.aot;

import io.koraframework.config.common.*;
import io.koraframework.config.common.ConfigValue.*;
import io.koraframework.config.common.annotation.*;
import io.koraframework.config.common.impl.*;
import io.koraframework.config.common.mapper.*;
import io.koraframework.config.common.origin.*;

import java.util.*;

public interface AotConfigModule extends ConfigModule, ConfigValueMapperModule {

    @ApplicationConfig
    default ConfigOrigin applicationConfigOrigin() {
        return new SimpleConfigOrigin("aot-compiled");
    }

    @ApplicationConfig
    default Config applicationConfig(@ApplicationConfig ConfigOrigin origin) {
        // Динамически будим статический блок сгенерированного Impl без прямой связи типов
        AotConfigEvaluator.loadAotOverrides();

        var valueOrigin = new SimpleConfigValueOrigin(origin, ConfigValuePath.root());
        var globalRootMap = new LinkedHashMap<String, ConfigValue<?>>();

        for (var entry : AotConfigEvaluator.getRuntimeOverrides().entrySet()) {
            AotConfigEvaluator.buildTreeFromFlatMap(globalRootMap, entry.getKey(), entry.getValue());
        }

        var globalObject = new ObjectValue(valueOrigin, globalRootMap);
        return new SimpleConfig(origin, globalObject);
    }
}
