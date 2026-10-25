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
                withFallback = ${?NON_EXISTENT_VAR}"default-fallback"
                envName = ${shared.environment}
                description = "Running in "${shared.environment}" mode"
                connectTimeout = ${shared.globalTimeout}
                modeExact = "ENABLED"
                modeRelaxed = "maintenance"
                releaseDate = "2026-10-25"
                createdAt = "2026-10-25T12:00:00"
                fromEnv = ${MY_ENV_VAR}
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
            import io.koraframework.config.common.annotation.*;
            
            import java.time.*;
            import java.util.*;
            
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
            
                default String defaultRootString() {
                    return "root-default-value";
                }
            
                default int defaultRootInt() {
                    return 42;
                }
            
                @ConfigMapper
                interface Level1Config {
                    String targetEnv();
                    Level2Config deeplyNested();
            
                    default String defaultNestedString() {
                        return "nested-default-value";
                    }
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

            assertThat(appConfig.get(PathElement.get("defaultRootString")).isNull()).isTrue();
            assertThat(appConfig.get(PathElement.get("defaultRootInt")).isNull()).isTrue();
            assertThat(nested1.get(PathElement.get("defaultNestedString")).isNull()).isTrue();

        } finally {
            System.clearProperty("MY_ENV_VAR");
            System.clearProperty("NESTED_ENV_VAR");
            System.clearProperty("app.connectTimeout");
            System.clearProperty("REQUIRED_ENV_VALUE");
        }
    }

    @Test
    public void testAllPrimitivesAndNumericExtremes() throws Exception {
        var baseConf = """
            app {
                // Целые числа
                byteVal = 127
                shortVal = 32767
                intVal = 2147483647
                bigId = 99999999999
                smallId = -1234567890123
            
                // Обертки над целыми числами
                boxedByteVal = -128
                boxedShortVal = -32768
                boxedIntVal = -2147483648
                boxedLongVal = 500000000000
            
                // Дробные числа и спец-форматы
                floatVal = 3.14
                doubleVal = 3.141592653589793
                scientific = 1e-5
                scientificLarge = 2.5e+6
                boxedFloatVal = -1.1
                boxedDoubleVal = -99.999
            
                // Логические значения и символы
                boolVal = true
                boxedBoolVal = false
                charVal = "A"
                boxedCharVal = "Z"
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            @ConfigSource("app")
            public interface AllPrimitivesConfig {
                byte byteVal();
                short shortVal();
                int intVal();
                long bigId();
                long smallId();
            
                Byte boxedByteVal();
                Short boxedShortVal();
                Integer boxedIntVal();
                Long boxedLongVal();
            
                float floatVal();
                double doubleVal();
                double scientific();
                double scientificLarge();
                Float boxedFloatVal();
                Double boxedDoubleVal();
            
                boolean boolVal();
                Boolean boxedBoolVal();
                char charVal();
                Character boxedCharVal();
            }
            """);

        var moduleClass = compileResult.loadClass("AllPrimitivesConfigAotModule");
        var moduleInstance = Proxy.newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null
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

        var factoryMethod = moduleClass.getMethod("allPrimitivesConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);
        factoryMethod.invoke(moduleInstance, mapperMock);

        var appConfig = capturedValueRef.get();
        assertThat(appConfig).isNotNull();

        assertThat(appConfig.get(PathElement.get("byteVal")).asNumber().byteValue()).isEqualTo((byte) 127);
        assertThat(appConfig.get(PathElement.get("shortVal")).asNumber().shortValue()).isEqualTo((short) 32767);
        assertThat(appConfig.get(PathElement.get("intVal")).asNumber().intValue()).isEqualTo(2147483647);
        assertThat(appConfig.get(PathElement.get("bigId")).asNumber().longValue()).isEqualTo(99999999999L);
        assertThat(appConfig.get(PathElement.get("smallId")).asNumber().longValue()).isEqualTo(-1234567890123L);

        assertThat(appConfig.get(PathElement.get("boxedByteVal")).asNumber().byteValue()).isEqualTo((byte) -128);
        assertThat(appConfig.get(PathElement.get("boxedShortVal")).asNumber().shortValue()).isEqualTo((short) -32768);
        assertThat(appConfig.get(PathElement.get("boxedIntVal")).asNumber().intValue()).isEqualTo(-2147483648);
        assertThat(appConfig.get(PathElement.get("boxedLongVal")).asNumber().longValue()).isEqualTo(500000000000L);

        assertThat(appConfig.get(PathElement.get("floatVal")).asNumber().floatValue()).isEqualTo(3.14f);
        assertThat(appConfig.get(PathElement.get("doubleVal")).asNumber().doubleValue()).isEqualTo(3.141592653589793);
        assertThat(appConfig.get(PathElement.get("scientific")).asNumber().doubleValue()).isEqualTo(1e-5);
        assertThat(appConfig.get(PathElement.get("scientificLarge")).asNumber().doubleValue()).isEqualTo(2.5e+6);
        assertThat(appConfig.get(PathElement.get("boxedFloatVal")).asNumber().floatValue()).isEqualTo(-1.1f);
        assertThat(appConfig.get(PathElement.get("boxedDoubleVal")).asNumber().doubleValue()).isEqualTo(-99.999);

        assertThat(appConfig.get(PathElement.get("boolVal")).asBoolean()).isTrue();
        assertThat(appConfig.get(PathElement.get("boxedBoolVal")).asBoolean()).isFalse();
        assertThat(appConfig.get(PathElement.get("charVal")).asString()).isEqualTo("A");
        assertThat(appConfig.get(PathElement.get("boxedCharVal")).asString()).isEqualTo("Z");
    }

    @Test
    public void testEnumCollectionsAndEnumKeysInMaps() throws Exception {
        var baseConf = """
            app {
                activeModes = ["enabled", "mAiNtEnAnCe"]
                modesSet = ["disabled", "ENABLED"]
            
                modeToValue = {
                    "enabled" = "active"
                    "disabled" = "inactive"
                    "maintenance" = "warn"
                }
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            import java.util.List;
            import java.util.Set;
            import java.util.Map;
            
            @ConfigSource("app")
            public interface EnumConfig {
                enum FeatureMode {
                    ENABLED,
                    DISABLED,
                    MAINTENANCE
                }
                List<FeatureMode> activeModes();
                Set<FeatureMode> modesSet();
                Map<FeatureMode, String> modeToValue();
            }
            """);

        var moduleClass = compileResult.loadClass("EnumConfigAotModule");
        var moduleInstance = Proxy.newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null
        );
        var capturedValueRef = new AtomicReference<ConfigValue.ObjectValue>();
        var mapperMock = (ConfigValueMapper<?>) Proxy.newProxyInstance(
            ConfigValueMapper.class.getClassLoader(),
            new Class<?>[] {ConfigValueMapper.class},
            (_, method, args) -> {
                if (method.getName().equals("map") && args != null && args.length > 0 && args[0] instanceof ConfigValue.ObjectValue) {
                    capturedValueRef.set((ConfigValue.ObjectValue) args[0]);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("enumConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);
        factoryMethod.invoke(moduleInstance, mapperMock);

        var appConfig = capturedValueRef.get();
        assertThat(appConfig).isNotNull();

        var activeModes = appConfig.get(PathElement.get("activeModes")).asArray();
        assertThat(activeModes).hasSize(2);
        assertThat(activeModes.get(0).asString()).isEqualTo("enabled");
        assertThat(activeModes.get(1).asString()).isEqualTo("mAiNtEnAnCe");

        var modesSet = appConfig.get(PathElement.get("modesSet")).asArray();
        assertThat(modesSet).hasSize(2);

        var modeToValue = appConfig.get(PathElement.get("modeToValue")).asObject();
        assertThat(modeToValue.get(PathElement.get("enabled")).asString()).isEqualTo("active");
        assertThat(modeToValue.get(PathElement.get("disabled")).asString()).isEqualTo("inactive");
        assertThat(modeToValue.get(PathElement.get("maintenance")).asString()).isEqualTo("warn");
    }

    @Test
    public void testComplexMapsWithNonStringKeysAndNestedObjects() throws Exception {
        var baseConf = """
            app {
                intToValue = {
                    "100" = "hundred"
                    "200" = "two hundred"
                }
                dynamicObjects = {
                    "service-a" = { p1 = "v1", p2 = "v2" }
                    "service-b" = { p1 = "v3", p2 = "v4" }
                }
            }
            """;

        compileAotConfig("application.conf", baseConf, """
            import io.koraframework.config.common.annotation.*;
            
            import java.util.*;
            
            @ConfigSource("app")
            public interface ComplexMapConfig {
                Map<Integer, String> intToValue();
                Map<String, DynamicObj> dynamicObjects();
            
                default Map<String, String> defaultMap() {
                    return Map.of("defaultKey", "defaultValue");
                }
            
                @ConfigMapper
                interface DynamicObj {
                    String p1();
                    String p2();
                }
            }
            """);

        var moduleClass = compileResult.loadClass("ComplexMapConfigAotModule");
        var moduleInstance = Proxy.newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null
        );
        var capturedValueRef = new AtomicReference<ConfigValue.ObjectValue>();
        var mapperMock = (ConfigValueMapper<?>) Proxy.newProxyInstance(
            ConfigValueMapper.class.getClassLoader(),
            new Class<?>[] {ConfigValueMapper.class},
            (proxy, method, args) -> {
                if (method.getName().equals("map") && args != null && args.length > 0 && args[0] instanceof ConfigValue.ObjectValue) {
                    capturedValueRef.set((ConfigValue.ObjectValue) args[0]);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("complexMapConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);
        factoryMethod.invoke(moduleInstance, mapperMock);

        var appConfig = capturedValueRef.get();
        assertThat(appConfig).isNotNull();

        var intToValue = appConfig.get(PathElement.get("intToValue")).asObject();
        assertThat(intToValue.get(PathElement.get("100")).asString()).isEqualTo("hundred");
        assertThat(intToValue.get(PathElement.get("200")).asString()).isEqualTo("two hundred");

        var dynamicObjects = appConfig.get(PathElement.get("dynamicObjects")).asObject();
        var serviceA = dynamicObjects.get(PathElement.get("service-a")).asObject();
        assertThat(serviceA.get(PathElement.get("p1")).asString()).isEqualTo("v1");
        var serviceB = dynamicObjects.get(PathElement.get("service-b")).asObject();
        assertThat(serviceB.get(PathElement.get("p1")).asString()).isEqualTo("v3");

        assertThat(appConfig.get(PathElement.get("defaultMap")).isNull()).isTrue();
    }

    @Test
    public void testComplexObjectsInListDynamicExtensionAndMutation() throws Exception {
        var baseConf = """
            app {
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
            import java.util.List;
            import io.koraframework.config.common.annotation.ConfigMapper;
            
            @ConfigSource("app")
            public interface ListObjectsOverrideConfig {
                List<NestedObj> propObjects();
            
                @ConfigMapper
                interface NestedObj {
                    String p1();
                    String p2();
                }
            }
            """);

        var moduleClass = compileResult.loadClass("ListObjectsOverrideConfigAotModule");
        var moduleInstance = Proxy.newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null
        );

        var capturedValueRef = new AtomicReference<ConfigValue.ObjectValue>();
        var mapperMock = (ConfigValueMapper<?>) Proxy.newProxyInstance(
            ConfigValueMapper.class.getClassLoader(),
            new Class<?>[] {ConfigValueMapper.class},
            (proxy, method, args) -> {
                if (method.getName().equals("map") && args != null && args.length > 0 && args[0] instanceof ConfigValue.ObjectValue) {
                    capturedValueRef.set((ConfigValue.ObjectValue) args[0]);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("listObjectsOverrideConfig", io.koraframework.config.common.mapper.ConfigValueMapper.class);

        System.setProperty("app.propObjects.0.p1", "v1-overridden");
        System.setProperty("app.propObjects.2.p1", "v5-new");
        System.setProperty("app.propObjects.2.p2", "v6-new");

        try {
            factoryMethod.invoke(moduleInstance, mapperMock);
            var appConfig = capturedValueRef.get();
            assertThat(appConfig).isNotNull();

            var propObjects = appConfig.get(PathElement.get("propObjects")).asArray();

            assertThat(propObjects).hasSize(3);

            var obj0 = propObjects.get(0).asObject();
            assertThat(obj0.get(PathElement.get("p1")).asString()).isEqualTo("v1-overridden");
            assertThat(obj0.get(PathElement.get("p2")).asString()).isEqualTo("v2");

            var obj1 = propObjects.get(1).asObject();
            assertThat(obj1.get(PathElement.get("p1")).asString()).isEqualTo("v3");
            assertThat(obj1.get(PathElement.get("p2")).asString()).isEqualTo("v4");

            var obj2 = propObjects.get(2).asObject();
            assertThat(obj2.get(PathElement.get("p1")).asString()).isEqualTo("v5-new");
            assertThat(obj2.get(PathElement.get("p2")).asString()).isEqualTo("v6-new");

        } finally {
            System.clearProperty("app.propObjects.0.p1");
            System.clearProperty("app.propObjects.2.p1");
            System.clearProperty("app.propObjects.2.p2");
        }
    }
}
