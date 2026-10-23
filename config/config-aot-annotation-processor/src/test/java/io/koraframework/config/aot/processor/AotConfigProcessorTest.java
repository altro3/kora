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
        assertThat(moduleClass.isAnnotationPresent(io.koraframework.common.annotation.Module.class)).isTrue();

        var globalModuleClass = compileResult.loadClass("AotConfigModule");
        assertThat(globalModuleClass).isInterface();
        assertThat(globalModuleClass.isAssignableFrom(moduleClass)).isTrue();

        var implClass = compileResult.loadClass("$TestConfig_ConfigValueMapper.TestConfig_Impl");
        
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
}
