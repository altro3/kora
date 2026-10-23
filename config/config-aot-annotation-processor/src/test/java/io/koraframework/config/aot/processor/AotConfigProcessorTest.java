package io.koraframework.config.aot.processor;

import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

import static java.lang.reflect.Proxy.*;
import static org.assertj.core.api.Assertions.*;

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

        var moduleInstance = newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
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

            var moduleInstance = newProxyInstance(
                moduleClass.getClassLoader(),
                new Class<?>[] {moduleClass},
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
        // Пишем в "базовый" конфиг значение "from-file"
        var yamlContent = """
            app:
              host: "from-file"
            """;

        compileAotConfig("application.yaml", yamlContent, """
            @ConfigSource("app")
            public interface TestConfig {
                String host();
            }
            """);

        // 1. Загружаем сгенерированный компилятором АОТ-модуль
        var moduleClass = compileResult.loadClass("TestConfigAotModule");
        var implClass = compileResult.loadClass("$TestConfig_ConfigValueMapper$TestConfig_Impl");

        // 2. Создаем экземпляр интерфейса-модуля для вызова default методов
        var moduleInstance = newProxyInstance(
            moduleClass.getClassLoader(),
            new Class<?>[] {moduleClass},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                }
                return null;
            }
        );

        var factoryMethod = moduleClass.getMethod("testConfig");

        // --- ТЕСТ 1: Значение должно прийти из скомпилированного YAML файла ---
        var config1 = factoryMethod.invoke(moduleInstance);
        var hostMethod = config1.getClass().getMethod("host");
        assertThat(hostMethod.invoke(config1)).isEqualTo("from-file");

        // --- ТЕСТ 2: System Property должно перебить значение из файла ---
        System.setProperty("APP_HOST", "from-sys"); // для sysKey "app.host" или envKey "APP_HOST"
        System.setProperty("app.host", "from-sys");
        try {
            var config2 = factoryMethod.invoke(moduleInstance);
            assertThat(hostMethod.invoke(config2)).isEqualTo("from-sys");
        } finally {
            System.clearProperty("APP_HOST");
            System.clearProperty("app.host");
        }
    }

@Test
public void testAotConfigComplexProfilesAndOverrides() throws Exception {
    var baseYaml = """
        app:
          host: "base-host"
          port: 1111
          db-name: "base-db"
          cache-size: 100
          timeout: 10
        """;

    var commonYaml = """
        app:
          db-name: "common-db"
          cache-size: 200
          timeout: 20
        """;

    var localYaml = """
        app:
          cache-size: 300
          timeout: 30
        """;

    var devYaml = """
        app:
          timeout: 40
        """;

    try {
        var targetDir = Paths.get("build", "in-test-generated", "classes");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("application.yaml"), baseYaml);
        Files.writeString(targetDir.resolve("application-common.yaml"), commonYaml);
        Files.writeString(targetDir.resolve("application-local.yaml"), localYaml);
        Files.writeString(targetDir.resolve("application-dev.yaml"), devYaml);
    } catch (IOException e) {
        throw new RuntimeException(e);
    }

    // Запускаем компиляцию интерфейса конфигурации
    compileAotConfig("application.yaml", baseYaml, """
        @ConfigSource("app")
        public interface ComplexConfig {
            String host();       // Останется из базового конфига ("base-host")
            int port();          // Переопределится через System Property (например, 9999)
            String dbName();     // Придет из профиля "common" ("common-db")
            int cacheSize();     // Придет из профиля "local" ("local-size" -> 300), перебив "common"
            int timeout();       // Переопределится через ENV переменную (например, 555)
            default boolean enabled() { return true; } // Дефолт из интерфейса
        }
        """);

    // 2. Загружаем сгенерированные АОТ классы
    var moduleClass = compileResult.loadClass("ComplexConfigAotModule");

    var moduleInstance = newProxyInstance(
        moduleClass.getClassLoader(),
        new Class<?>[] {moduleClass},
        (proxy, method, args) -> {
            if (method.isDefault()) {
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            }
            return null;
        }
    );

    var factoryMethod = moduleClass.getMethod("complexConfig");

    // 3. Выставляем рантайм-переменные (Системные свойства и Профили)
    System.setProperty("kora.profiles", "common,local"); // Активные профили
    System.setProperty("app.port", "9999");              // Переопределение через System Property

    // Для ENV-переменных: напрямую System.getenv() в Java поменять нельзя (он unmodifiable),
    // Но наш класс AotConfigResolvers.resolveRaw ТАКЖЕ проверяет System.getProperty(envKey)
    // перед тем как сдаться, либо мы можем симулировать ENV через подмену (в нашем АОТ-резолвере envKey проверяется первым).
    // Поскольку у нас в resolveRaw написано: `var env = System.getenv(envKey); if (env != null) return env;`
    // Мы проверим абсолютный приоритет на примере стандартной переменной PATH, если захотим,
    // но для теста логики фреймворка проверим то, что можем выставить: Профили и Sys Property.

    try {
        // Вызываем фабрику создания конфигурации
        var configInstance = factoryMethod.invoke(moduleInstance);

        // Достаем методы созданного POJO-объекта конфигурации
        var hostMethod = configInstance.getClass().getMethod("host");
        var portMethod = configInstance.getClass().getMethod("port");
        var dbNameMethod = configInstance.getClass().getMethod("dbName");
        var cacheSizeMethod = configInstance.getClass().getMethod("cacheSize");
        var timeoutMethod = configInstance.getClass().getMethod("timeout");
        var enabledMethod = configInstance.getClass().getMethod("enabled");

        // 4. Проверяем утверждения (МАТРИЦА ПРИОРИТЕТОВ)

        // Свойство 'host' нигде не переопределено -> берется из базового application.yaml
        assertThat(hostMethod.invoke(configInstance)).isEqualTo("base-host");

        // Свойство 'port' задано в базе (1111), но перебито через System Property -> 9999
        assertThat(portMethod.invoke(configInstance)).isEqualTo(9999);

        // Свойство 'dbName' переопределено в профиле 'common' -> "common-db"
        assertThat(dbNameMethod.invoke(configInstance)).isEqualTo("common-db");

        // Свойство 'cacheSize' есть и в 'common' (200), и в 'local' (300).
        // Так как 'local' идет в списке профилей позже ("common,local"), он побеждает -> 300
        assertThat(cacheSizeMethod.invoke(configInstance)).isEqualTo(300);

        // Свойство 'timeout' есть в базе, common, local и dev (40).
        // Но dev НЕ активен, активны common,local -> побеждает последний активный ('local') -> 30
        assertThat(timeoutMethod.invoke(configInstance)).isEqualTo(30);

        // Свойство 'enabled' отсутствует во всех файлах и переменных -> берется дефолт интерфейса -> true
        assertThat(enabledMethod.invoke(configInstance)).isEqualTo(true);

    } finally {
        // Обязательно чистим за собой систему после теста
        System.clearProperty("kora.profiles");
        System.clearProperty("app.port");

        // Чистим файлы на диске
        var targetDir = Paths.get("build", "in-test-generated", "classes");
        Files.deleteIfExists(targetDir.resolve("application.yaml"));
        Files.deleteIfExists(targetDir.resolve("application-common.yaml"));
        Files.deleteIfExists(targetDir.resolve("application-local.yaml"));
        Files.deleteIfExists(targetDir.resolve("application-dev.yaml"));
    }
}
}
