package io.koraframework.kora.app.annotation.processor;

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
        int nodesCount = 250;  // Реалистичный размер боевого микросервиса
        int layersCount = 5;   // 5 слоев архитектуры по твоим правилам

        // 1. Распределяем ноды по слоям (равномерно по ~120 нод на слой)
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

        // 2. Генерируем Java-код графа по твоим строгим правилам
        var sb = new StringBuilder("\n")
            .append("@KoraApp\n")
            .append("public interface ExampleApplication {\n");

        // Уникальные типы-маркеры
        for (int i = 0; i < nodesCount; i++) {
            sb.append("  interface Type").append(i).append(" {}\n");
        }

        var random = new java.util.Random(42); // Фиксированный seed

        for (int i = 0; i < nodesCount; i++) {
            int layer = nodeToLayerMap[i];

            // Контроллеры (слой 4) и 20% сервисов помечаем как @Root
            if (layer == layersCount - 1 || (layer > 1 && i % 5 == 0)) {
                sb.append("  @Root\n");
            }

            sb.append("  default Type").append(i).append(" component").append(i).append("(");

            java.util.List<Integer> deps = new java.util.ArrayList<>();

            if (layer == 0) {
                // Слой 0 (Properties): зависимостей нет
            } else if (layer == 1) {
                // Слой 1 (Repositories): зависят только от Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));
                deps.add(propNodes.get((i + 1) % propNodes.size()));
            } else if (layer == layersCount - 1) {
                // Слой 4 (Controllers): только на нижний слой (Слой 3) и Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                var prevLayerNodes = layerToNodesMap[layersCount - 2]; // Слой 3 (Services)
                deps.add(prevLayerNodes.get(i % prevLayerNodes.size()));
                deps.add(prevLayerNodes.get((i + 1) % prevLayerNodes.size()));
            } else {
                // Слой 2 и 3 (Services/Components): могут ссылаться на всё, кроме контроллеров

                // А. Ссылаемся на Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                // Б. Ссылаемся на Repositories (Слой 1)
                var repoNodes = layerToNodesMap[1];
                deps.add(repoNodes.get(i % repoNodes.size()));

                // В. Ссылаемся на предыдущие Services (если мы на Слое 3, можем сослаться на Слой 2)
                if (layer == 3) {
                    var serviceLayer2Nodes = layerToNodesMap[2];
                    deps.add(serviceLayer2Nodes.get(i % serviceLayer2Nodes.size()));
                }

                // Г. НАСТОЯЩИЙ ПОЛНОЦЕННЫЙ ГРАФ: Горизонтальные связи внутри текущего слоя
                var currentLayerNodes = layerToNodesMap[layer];
                int internalIdx = currentLayerNodes.indexOf(i);
                if (internalIdx > 2) {
                    // Зависим от нескольких соседей слева на своем же этаже архитектуры
                    deps.add(currentLayerNodes.get(internalIdx - 1));
                    deps.add(currentLayerNodes.get(internalIdx - 2));
                }
            }

            // Записываем аргументы в метод
            for (int d = 0; d < deps.size(); d++) {
                int depId = deps.get(d);
                sb.append("Type").append(depId).append(" dep").append(depId);
                if (d < deps.size() - 1) sb.append(", ");
            }

            sb.append(") { return new Type").append(i).append("() {}; }\n");
        }
        sb.append("}\n");

        // 3. Компиляция и замер
        var draw = compile(sb.toString());
        assertThat(draw.getNodes()).hasSize(nodesCount);

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
