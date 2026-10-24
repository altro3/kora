package io.koraframework.config.aot.processor;

import io.koraframework.config.common.ConfigValue;
import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.*;

public class AotConfigHoconProcessorTest extends AbstractAotConfigTest {

    @Test
    public void testAotConfigComplexOverrides() throws Exception {
        var baseConf = """
            app {
              host = "base-host"
              port = 1111
              dbName = "base-db"
              cacheSize = 100
              timeout = 10
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            @ConfigSource("app")
            public interface ComplexConfig {
                String host();
                int port();
                String dbName();
                int cacheSize();
                int timeout();
                default boolean enabled() { return true; }
            }
            """);

        var moduleClass = compileResult.loadClass("ComplexConfigAotModule");

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

        var mapperImplClass = compileResult.loadClass("$ComplexConfig_ConfigValueMapper");
        var mapperInstance = mapperImplClass.getConstructor().newInstance();

        var factoryMethod = moduleClass.getMethod("complexConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);

        System.setProperty("app.port", "9999");
        System.setProperty("app.dbName", "sys-db");
        System.setProperty("APP_TIMEOUT", "555");

        try {
            var configInstance = factoryMethod.invoke(moduleInstance, mapperInstance);

            var hostMethod = configInstance.getClass().getMethod("host");
            var portMethod = configInstance.getClass().getMethod("port");
            var dbNameMethod = configInstance.getClass().getMethod("dbName");
            var cacheSizeMethod = configInstance.getClass().getMethod("cacheSize");
            var timeoutMethod = configInstance.getClass().getMethod("timeout");
            var enabledMethod = configInstance.getClass().getMethod("enabled");

            assertThat(hostMethod.invoke(configInstance)).isEqualTo("base-host");
            assertThat(portMethod.invoke(configInstance)).isEqualTo(9999);
            assertThat(dbNameMethod.invoke(configInstance)).isEqualTo("sys-db");
            assertThat(cacheSizeMethod.invoke(configInstance)).isEqualTo(100);
            assertThat(timeoutMethod.invoke(configInstance)).isEqualTo(555);
            assertThat(enabledMethod.invoke(configInstance)).isEqualTo(true);

        } finally {
            System.clearProperty("app.port");
            System.clearProperty("app.dbName");
            System.clearProperty("APP_TIMEOUT");
        }
    }
}
