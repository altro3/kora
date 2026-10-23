package io.koraframework.config.aot.processor;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import static org.assertj.core.api.Assertions.assertThat;

public class AotConfigProcessorTest extends AbstractAotConfigTest {

    @Test
    public void testAotConfigGenerationWithYaml() throws Exception {
        var yamlContent = """
                app:
                  host: "localhost"
                  port: 9090
                """;

        compileAotConfig("application.yaml", yamlContent, """
                @ConfigSource("app")
                public interface TestConfig {
                    String host();
                    int port();
                    default boolean enabled() { return true; }
                }
                """);

        var moduleClass = compileResult.loadClass("TestConfigAotModule");
        assertThat(moduleClass).isInterface();

        var globalModuleClass = compileResult.loadClass("AotConfigModule");
        assertThat(globalModuleClass).isInterface();

        var implClass = compileResult.loadClass("$TestConfig_ConfigValueMapper$TestConfig_Impl");

        var moduleInstance = Proxy.newProxyInstance(
                moduleClass.getClassLoader(),
                new Class<?>[]{moduleClass},
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return null;
                }
        );

        var factoryMethod = moduleClass.getMethod("testConfig");
        var configInstance = factoryMethod.invoke(moduleInstance);

        assertThat(configInstance).isInstanceOf(implClass);

        var hostMethod = configInstance.getClass().getMethod("host");
        var portMethod = configInstance.getClass().getMethod("port");
        var enabledMethod = configInstance.getClass().getMethod("enabled");

        assertThat(hostMethod.invoke(configInstance)).isEqualTo("localhost");
        assertThat(portMethod.invoke(configInstance)).isEqualTo(9090);
        assertThat(enabledMethod.invoke(configInstance)).isEqualTo(true);
    }

    @Test
    public void testAotConfigWithSystemProperties() throws Exception {
        System.setProperty("app.host", "sys-host");
        System.setProperty("app.port", "7070");

        try {
            compileAotConfig("application.yaml", "", """
                    @ConfigSource("app")
                    public interface TestConfig {
                        String host();
                        int port();
                        default boolean enabled() { return true; }
                    }
                    """);

            var moduleClass = compileResult.loadClass("TestConfigAotModule");
            var implClass = compileResult.loadClass("$TestConfig_ConfigValueMapper$TestConfig_Impl");

            var moduleInstance = Proxy.newProxyInstance(
                moduleClass.getClassLoader(),
                new Class<?>[]{moduleClass},
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return null;
                }
            );

            var factoryMethod = moduleClass.getMethod("testConfig");
            var configInstance = factoryMethod.invoke(moduleInstance);

            assertThat(configInstance).isInstanceOf(implClass);

            var hostMethod = configInstance.getClass().getMethod("host");
            var portMethod = configInstance.getClass().getMethod("port");
            var enabledMethod = configInstance.getClass().getMethod("enabled");

            assertThat(hostMethod.invoke(configInstance)).isEqualTo("sys-host");
            assertThat(portMethod.invoke(configInstance)).isEqualTo(7070);
            assertThat(enabledMethod.invoke(configInstance)).isEqualTo(true);

        } finally {
            System.clearProperty("app.host");
            System.clearProperty("app.port");
        }
    }

    @Test
    public void testAotMethodsPriorityDirectly() throws Exception {
        compileAotConfig("application.yaml", "", """
                @ConfigSource("app")
                public interface TestConfig {
                    String host();
                }
                """);

        var moduleClass = compileResult.loadClass("TestConfigAotModule");

        var resolveStringMethod = moduleClass.getDeclaredMethod("resolveString",
            String.class, String.class, String.class, String.class);
        resolveStringMethod.setAccessible(true);

        System.setProperty("test.sys.key", "from-sys");
        try {
            var res1 = resolveStringMethod.invoke(null, "NON_EXISTENT_ENV", "non.existent.sys", null, "default");
            assertThat(res1).isEqualTo("default");

            var res2 = resolveStringMethod.invoke(null, "NON_EXISTENT_ENV", "non.existent.sys", "from-file", "default");
            assertThat(res2).isEqualTo("from-file");

            var res3 = resolveStringMethod.invoke(null, "NON_EXISTENT_ENV", "test.sys.key", "from-file", "default");
            assertThat(res3).isEqualTo("from-sys");

            var actualPathEnv = System.getenv("PATH");
            if (actualPathEnv != null) {
                var res4 = resolveStringMethod.invoke(null, "PATH", "test.sys.key", "from-file", "default");
                assertThat(res4).isEqualTo(actualPathEnv);
            }
        } finally {
            System.clearProperty("test.sys.key");
        }
    }
}
