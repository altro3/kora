package io.koraframework.config.aot.processor;

import io.koraframework.config.common.ConfigValue;
import io.koraframework.config.common.PathElement;
import io.koraframework.config.common.mapper.ConfigValueMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

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
        import java.time.LocalDate;
        import java.time.LocalDateTime;
        import java.time.Duration;
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
        }
        """);

        var moduleClass = compileResult.loadClass("RichConfigAotModule");

        // 1. Создаем инстанс модуля
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

        // 2. Перехватываем дерево ConfigValue, которое сгенерированный метод отдаст в маппер
        var capturedValueRef = new AtomicReference<ConfigValue.ObjectValue>();
        var mapperMock = (ConfigValueMapper<?>) Proxy.newProxyInstance(
            ConfigValueMapper.class.getClassLoader(),
            new Class<?>[]{ConfigValueMapper.class},
            (proxy, method, args) -> {
                if (method.getName().equals("map") && args.length > 0 && args[0] instanceof ConfigValue.ObjectValue) {
                    capturedValueRef.set((ConfigValue.ObjectValue) args[0]);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("richConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);

        // 3. Выставляем свойства для рантайм-разрешения оверрайдов внутри resolveAndPut
        System.setProperty("MY_ENV_VAR", "env-value");
        System.setProperty("NESTED_ENV_VAR", "nested-env-value");
        System.setProperty("app.connectTimeout", "15s");

        try {
            // Запускаем метод сгенерированного AOT-модуля
            factoryMethod.invoke(moduleInstance, mapperMock);

            var appConfig = capturedValueRef.get();
            assertThat(appConfig).isNotNull();

            // 4. Проверяем, что AotConfigResolvers собрал структуру без ошибок и применил оверрайды
            assertThat(appConfig.get(PathElement.get("envName")).asString()).isEqualTo("production");
            assertThat(appConfig.get(PathElement.get("description")).asString()).isEqualTo("Running in production mode");
            assertThat(appConfig.get(PathElement.get("connectTimeout")).asString()).isEqualTo("15s"); // Перебито рантайм оверрайдом

            assertThat(appConfig.get(PathElement.get("fromEnv")).asString()).isEqualTo("env-value"); // Из переменной окружения
            assertThat(appConfig.get(PathElement.get("withFallback")).asString()).isEqualTo("default-fallback");

            assertThat(appConfig.get(PathElement.get("modeExact")).asString()).isEqualTo("ENABLED");
            assertThat(appConfig.get(PathElement.get("modeRelaxed")).asString()).isEqualTo("maintenance");

            assertThat(appConfig.get(PathElement.get("releaseDate")).asString()).isEqualTo("2026-10-25");
            assertThat(appConfig.get(PathElement.get("createdAt")).asString()).isEqualTo("2026-10-25T12:00:00");

            // 5. Проверяем глубокую вложенность структуры дерева объектов
            var nested1 = appConfig.get(PathElement.get("nested")).asObject();
            assertThat(nested1).isNotNull();
            assertThat(nested1.get(PathElement.get("targetEnv")).asString()).isEqualTo("production");

            var nested2 = nested1.get(PathElement.get("deeplyNested")).asObject();
            assertThat(nested2).isNotNull();
            assertThat(nested2.get(PathElement.get("finalUrl")).asString()).isEqualTo("https://production.mycompany.com");
            assertThat(nested2.get(PathElement.get("nestedFromEnv")).asString()).isEqualTo("nested-env-value"); // Из глубокого оверрайда

        } finally {
            System.clearProperty("MY_ENV_VAR");
            System.clearProperty("NESTED_ENV_VAR");
            System.clearProperty("app.connectTimeout");
        }
    }
}
