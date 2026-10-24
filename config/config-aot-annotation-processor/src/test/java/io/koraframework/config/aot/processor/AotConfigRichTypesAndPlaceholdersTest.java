package io.koraframework.config.aot.processor;

import io.koraframework.config.common.*;
import io.koraframework.config.common.mapper.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

public class AotConfigRichTypesAndPlaceholdersTest extends AbstractAotConfigTest {

    @Test
    public void testPlaceholdersEnumsDatesCollectionsAndHoconFeatures() throws Exception {
        var baseConf = """
            shared {
                environment = "production"
                globalTimeout = "5s"
            }
            
            app {
                envName = ${shared.environment}
                description = "Running in "${shared.environment}" mode"
                connectTimeout = ${shared.globalTimeout}
                modeExact = "ENABLED"
                modeRelaxed = "maintenance"
                releaseDate = "2026-10-25"
                createdAt = "2026-10-25T12:00:00"
                fromEnv = ${MY_ENV_VAR}
                withFallback = ${?NON_EXISTENT_VAR}"default-fallback"
                nested {
                    targetEnv = ${shared.environment}
                    deeplyNested {
                        finalUrl = "https://"${shared.environment}".mycompany.com"
                        nestedFromEnv = ${NESTED_ENV_VAR}
                    }
                }
                bar = "SomeValue"
                baz = 10
                propRequired = ${REQUIRED_ENV_VALUE}
                propOptional = ${?OPTIONAL_ENV_VALUE}
                propDefault = 10
                propDefault = ${?NON_DEFAULT_ENV_VALUE}
                propReference = ${app.bar}Other${app.baz}
                propArray = ["v1", "v2"]
                propArrayAsString = "v1, v2"
                propMap = {
                    "k1" = "v1"
                    "k2" = "v2"
                }
                propObject = {
                    p1 = "v1"
                    p2 = "v2"
                }
                propObjects = [
                    {
                        p1 = "v1"
                        p2 = "v2"
                    },
                    {
                        p1 = "v3"
                        p2 = "v4"
                    }
                ]
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            import java.time.LocalDate;
            import java.time.LocalDateTime;
            import java.time.Duration;
            import java.util.List;
            import java.util.Map;
            import io.koraframework.config.common.annotation.ConfigMapper;
            
            @ConfigSource("app")
            public interface RichConfig {
            
                enum FeatureMode {
                    ENABLED,
                    DISABLED,
                    MAINTENANCE
                }
            
                String envName();
                String description();
                Duration connectTimeout();
                FeatureMode modeExact();
                FeatureMode modeRelaxed();
                LocalDate releaseDate();
                LocalDateTime createdAt();
                String fromEnv();
                String withFallback();
                Level1Config nested();
                String bar();
                int baz();
                String propRequired();
                String propOptional();
                int propDefault();
                String propReference();
                List<String> propArray();
                String propArrayAsString();
                Map<String, String> propMap();
                NestedObj propObject();
                List<NestedObj> propObjects();
            
                @ConfigMapper
                interface Level1Config {
                    String targetEnv();
                    Level2Config deeplyNested();
                }
            
                @ConfigMapper
                interface Level2Config {
                    String finalUrl();
                    String nestedFromEnv();
                }
            
                @ConfigMapper
                interface NestedObj {
                    String p1();
                    String p2();
                }
            }
            """);

        var moduleClass = compileResult.loadClass("RichConfigAotModule");

        var moduleInstance = Proxy.newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                return null;
            }
        );

        var capturedValueRef = new AtomicReference<ConfigValue.ObjectValue>();
        var mapperMock = (ConfigValueMapper<?>) Proxy.newProxyInstance(
            ConfigValueMapper.class.getClassLoader(),
            new Class<?>[] {ConfigValueMapper.class},
            (_, method, args) -> {
                if (method.getName().equals("map") && args.length > 0 && args[0] instanceof ConfigValue.ObjectValue) {
                    capturedValueRef.set((ConfigValue.ObjectValue) args[0]);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("richConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);

        System.setProperty("MY_ENV_VAR", "env-value");
        System.setProperty("NESTED_ENV_VAR", "nested-env-value");
        System.setProperty("app.connectTimeout", "15s");
        System.setProperty("REQUIRED_ENV_VALUE", "essential-secure-value");

        try {
            factoryMethod.invoke(moduleInstance, mapperMock);

            var appConfig = capturedValueRef.get();
            assertThat(appConfig).isNotNull();

            assertThat(appConfig.get(PathElement.get("envName")).asString()).isEqualTo("production");
            assertThat(appConfig.get(PathElement.get("description")).asString()).isEqualTo("Running in production mode");
            assertThat(appConfig.get(PathElement.get("connectTimeout")).asString()).isEqualTo("15s");

            assertThat(appConfig.get(PathElement.get("fromEnv")).asString()).isEqualTo("env-value");
            assertThat(appConfig.get(PathElement.get("withFallback")).asString()).isEqualTo("default-fallback");

            assertThat(appConfig.get(PathElement.get("modeExact")).asString()).isEqualTo("ENABLED");
            assertThat(appConfig.get(PathElement.get("modeRelaxed")).asString()).isEqualTo("maintenance");

            assertThat(appConfig.get(PathElement.get("releaseDate")).asString()).isEqualTo("2026-10-25");
            assertThat(appConfig.get(PathElement.get("createdAt")).asString()).isEqualTo("2026-10-25T12:00:00");

            var nested1 = appConfig.get(PathElement.get("nested")).asObject();
            assertThat(nested1).isNotNull();
            assertThat(nested1.get(PathElement.get("targetEnv")).asString()).isEqualTo("production");

            var nested2 = nested1.get(PathElement.get("deeplyNested")).asObject();
            assertThat(nested2).isNotNull();
            assertThat(nested2.get(PathElement.get("finalUrl")).asString()).isEqualTo("https://production.mycompany.com");
            assertThat(nested2.get(PathElement.get("nestedFromEnv")).asString()).isEqualTo("nested-env-value");

            assertThat(appConfig.get(PathElement.get("bar")).asString()).isEqualTo("SomeValue");
            assertThat(appConfig.get(PathElement.get("baz")).asNumber().intValue()).isEqualTo(10);
            assertThat(appConfig.get(PathElement.get("propRequired")).asString()).isEqualTo("essential-secure-value");
            assertThat(appConfig.get(PathElement.get("propDefault")).asNumber().intValue()).isEqualTo(10);
            assertThat(appConfig.get(PathElement.get("propReference")).asString()).isEqualTo("SomeValueOther10");

            var propArray = appConfig.get(PathElement.get("propArray")).asArray();
            assertThat(propArray).hasSize(2);
            assertThat(propArray.get(0).asString()).isEqualTo("v1");

            var propMap = appConfig.get(PathElement.get("propMap")).asObject();
            assertThat(propMap.get(PathElement.get("k1")).asString()).isEqualTo("v1");

            var propObject = appConfig.get(PathElement.get("propObject")).asObject();
            assertThat(propObject.get(PathElement.get("p1")).asString()).isEqualTo("v1");

            var propObjects = appConfig.get(PathElement.get("propObjects")).asArray();
            assertThat(propObjects).hasSize(2);
            assertThat(propObjects.get(0).asObject().get(PathElement.get("p1")).asString()).isEqualTo("v1");
            assertThat(propObjects.get(1).asObject().get(PathElement.get("p1")).asString()).isEqualTo("v3");

        } finally {
            System.clearProperty("MY_ENV_VAR");
            System.clearProperty("NESTED_ENV_VAR");
            System.clearProperty("app.connectTimeout");
            System.clearProperty("REQUIRED_ENV_VALUE");
        }
    }
}
