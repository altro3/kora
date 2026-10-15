package io.koraframework.kora.app.annotation.processor;

import io.koraframework.application.graph.internal.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class KoraBigGraphTest extends AbstractKoraAppTest {
    @Test
    public void test() {
        var sb = new StringBuilder("\n")
            .append("@KoraApp\n")
            .append("public interface ExampleApplication {\n");
        for (int i = 0; i < 1500; i++) {
            sb.append("  @Root\n");
            sb.append("  default String declaration").append(i).append("() { return \"\"; }\n");
        }
        sb.append("}\n");
        var draw = compile(sb.toString());
        assertThat(draw.getNodes()).hasSize(1500);
        draw.init();
    }

    @Test
    public void testRealisticEnterpriseGraphColdStart() throws Exception {
        int nodesCount = 2500; // Наш новый масштаб
        int layersCount = 50;  // 50 слоев архитектуры

        int nodesPerLayer = nodesCount / layersCount;
        int[] nodeToLayerMap = new int[nodesCount];
        java.util.List<Integer>[] layerToNodesMap = new java.util.List[layersCount];
        for (int i = 0; i < layersCount; i++) {
            layerToNodesMap[i] = new java.util.ArrayList<>();
        }

        int currentLayer = 0;
        for (int i = 0; i < nodesCount; i++) {
            if (currentLayer < layersCount - 1 && layerToNodesMap[currentLayer].size() >= nodesPerLayer) {
                currentLayer++;
            }
            nodeToLayerMap[i] = currentLayer;
            layerToNodesMap[currentLayer].add(i);
        }

        var sb = new StringBuilder("\n")
            .append("@KoraApp\n")
            .append("public interface ExampleApplication {\n");

        // Генерируем ультралегкие интерфейсы-теги прямо внутри кода приложения
        for (int i = 0; i < nodesCount; i++) {
            sb.append("  interface Comp").append(i).append(" {}\n");
        }

        for (int i = 0; i < nodesCount; i++) {
            int layer = nodeToLayerMap[i];

            // Последний слой (Controllers) и каждая нода в цепочке должны быть достижимы
            if (layer == layersCount - 1 || i == nodesCount - 1) {
                sb.append("  @Root\n");
            }

            // Передаем класс-тег: @Tag(CompX.class)
            sb.append("  @Tag(Comp").append(i).append(".class)\n");
            sb.append("  default String component").append(i).append("(");

            java.util.List<Integer> deps = new java.util.ArrayList<>();

            if (layer == 0) {
                // Слой 0 (Properties): зависимостей нет
            } else if (layer == 1) {
                // Слой 1 (Repositories): зависят от Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));
            } else if (layer == layersCount - 1) {
                // Слой Controllers: жестко зависит от предыдущей ноды и Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                // Обязательная непрерывная связь с концом цепочки сервисов
                deps.add(i - 1);
            } else {
                // Слой 2, 3...48 (Services/Components):
                // 1. Связываем абсолютно ВСЕ ноды в единую неразрывную сквозную цепь-лестницу
                deps.add(i - 1);

                // 2. Добавляем системную зависимость от конфигурации (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                // 3. Вертикальная связь с репозиторием (Слой 1) для плотности
                var repoNodes = layerToNodesMap[1];
                deps.add(repoNodes.get(i % repoNodes.size()));
            }

            // Записываем аргументы с тегами через .class контракт
            for (int d = 0; d < deps.size(); d++) {
                int depId = deps.get(d);
                sb.append("@Tag(Comp").append(depId).append(".class) String dep").append(depId);
                if (d < deps.size() - 1) sb.append(", ");
            }

            sb.append(") { return \"\"; }\n");
        }
        sb.append("}\n");

        var draw = compile(sb.toString());
        assertThat(draw.getNodes()).hasSize(nodesCount);

//        for (int i = 0; i <= 100; i++) {
//            var warmupGraph = draw.init();
//            warmupGraph.release();
//        }

        var startNano = System.nanoTime();
        var graph = draw.init();
        var durationMs = (System.nanoTime() - startNano) / 1_000_000.0;

        System.out.printf("""
            ==================================================
            ❄️ KORA COLD START REALISTIC GRAPH BENCHMARK ❄️
            ==================================================
            🚀 Total Nodes    : %d (Realistic Microservice)
            📐 Graph Depth    : %d layers
            ⏱️ Startup Time   : %.2f ms
            ==================================================
            """, draw.size(), layersCount, durationMs);

        assertThat(graph).isNotNull();
        graph.release();
    }
}
