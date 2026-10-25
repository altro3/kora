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
        var valueOrigin = new SimpleConfigValueOrigin(origin, ConfigValuePath.root());
        var emptyObject = new ObjectValue(valueOrigin, new LinkedHashMap<>());

        return new SimpleConfig(origin, emptyObject);
    }
}
