package io.koraframework.config.aot.processor;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.time.*;

import static org.assertj.core.api.Assertions.*;

public class AotConfigRichTypesAndPlaceholdersTest extends AbstractAotConfigTest {

    @Test
    public void testPlaceholdersEnumsAndDates() throws Exception {
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
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            import java.time.*;
            
            @ConfigSource("app")
            public interface RichConfig {
            
                enum FeatureMode {
                    ENABLED,\s
                    DISABLED,\s
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
            
                interface Level1Config {
                    String targetEnv();
                    Level2Config deeplyNested();
                }
            
                interface Level2Config {
                    String finalUrl();
                    String nestedFromEnv();
                }
            }
            """);

        var moduleClass = compileResult.loadClass("RichConfigAotModule");
        var mapperImplClass = compileResult.loadClass("$RichConfig_ConfigValueMapper");
        var enumClass = compileResult.loadClass("RichConfig$FeatureMode");

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

        var mapperInstance = mapperImplClass.getConstructor().newInstance();
        var factoryMethod = moduleClass.getMethod("richConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);

        System.setProperty("MY_ENV_VAR", "env-value");
        System.setProperty("NESTED_ENV_VAR", "nested-env-value");
        System.setProperty("app.connectTimeout", "15s");

        try {
            var configInstance = factoryMethod.invoke(moduleInstance, mapperInstance);

            assertThat(invoke(configInstance, "envName")).isEqualTo("production");
            assertThat(invoke(configInstance, "description")).isEqualTo("Running in production mode");
            assertThat(invoke(configInstance, "connectTimeout")).isEqualTo(Duration.ofSeconds(15));

            assertThat(invoke(configInstance, "fromEnv")).isEqualTo("env-value");
            assertThat(invoke(configInstance, "withFallback")).isEqualTo("default-fallback");

            var enabledEnum = Enum.valueOf((Class<Enum>) enumClass, "ENABLED");
            var maintenanceEnum = Enum.valueOf((Class<Enum>) enumClass, "MAINTENANCE");

            assertThat(invoke(configInstance, "modeExact")).isEqualTo(enabledEnum);
            assertThat(invoke(configInstance, "modeRelaxed")).isEqualTo(maintenanceEnum);

            assertThat(invoke(configInstance, "releaseDate")).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(invoke(configInstance, "createdAt")).isEqualTo(LocalDateTime.of(2026, 10, 25, 12, 0, 0));

            var nested1 = invoke(configInstance, "nested");
            assertThat(nested1).isNotNull();
            assertThat(invoke(nested1, "targetEnv")).isEqualTo("production");

            var nested2 = invoke(nested1, "deeplyNested");
            assertThat(nested2).isNotNull();
            assertThat(invoke(nested2, "finalUrl")).isEqualTo("https://mycompany.com");
            assertThat(invoke(nested2, "nestedFromEnv")).isEqualTo("nested-env-value");

        } finally {
            System.clearProperty("MY_ENV_VAR");
            System.clearProperty("NESTED_ENV_VAR");
            System.clearProperty("app.connectTimeout");
        }
    }

    private Object invoke(Object target, String methodName) throws Exception {
        var method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
