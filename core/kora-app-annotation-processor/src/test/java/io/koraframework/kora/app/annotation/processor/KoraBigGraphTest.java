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
    public void testRealisticGraphColdStart() throws Exception {
        int nodesCount = 1500;
        int layersCount = 15; // Глубина архитектуры (15 ярусов)

        if (layersCount < 4) {
            throw new IllegalArgumentException("layersCount must be at least 4 for full layered architecture");
        }

        // 1. Распределяем ноды по слоям
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

        // 2. Генерируем Java-код графа
        var sb = new StringBuilder("\n")
            .append("@KoraApp\n")
            .append("public interface ExampleApplication {\n");

        // Уникальные интерфейсы-маркеры
        for (int i = 0; i < nodesCount; i++) {
            sb.append("  interface Type").append(i).append(" {}\n");
        }

        for (int i = 0; i < nodesCount; i++) {
            int layer = nodeToLayerMap[i];

            // Финальный слой (Контроллеры) гарантированно помечаем как @Root
            if (layer == layersCount - 1) {
                sb.append("  @Root\n");
            }

            sb.append("  default Type").append(i).append(" component").append(i).append("(");

            java.util.List<Integer> deps = new java.util.ArrayList<>();

            if (layer == 0) {
                // Properties: базовый слой, зависимостей нет
            } else if (layer == 1) {
                // Repositories: жестко привязываем к соответствующей по индексу ноде Properties
                int propIdx = i % layerToNodesMap[0].size();
                deps.add(layerToNodesMap[0].get(propIdx));
            } else if (layer == layersCount - 1) {
                // Controllers: каждый контроллер берет 1 свой базовый сервис + 1 свой базовый проперти
                int serviceLayer = layersCount - 2;
                int serviceIdx = i % layerToNodesMap[serviceLayer].size();
                deps.add(layerToNodesMap[serviceLayer].get(serviceIdx));

                int propIdx = i % layerToNodesMap[0].size();
                deps.add(layerToNodesMap[0].get(propIdx));
            } else {
                // Промежуточные Services: каждый сервис жестко зависит от сервиса/репозитория с предыдущего яруса
                int prevLayer = layer - 1;
                int prevLayerSize = layerToNodesMap[prevLayer].size();

                // Основная цепочка связи слоев (детерминированный шаг)
                int depIdx1 = i % prevLayerSize;
                deps.add(layerToNodesMap[prevLayer].get(depIdx1));

                // Дополнительный шаг для перекрестных связей, чтобы граф был плотным
                int depIdx2 = (i + 1) % prevLayerSize;
                if (depIdx1 != depIdx2) {
                    deps.add(layerToNodesMap[prevLayer].get(depIdx2));
                }

                // Каждый сервис также подтягивает конфигурацию (Слой 0)
                int propIdx = i % layerToNodesMap[0].size();
                deps.add(layerToNodesMap[0].get(propIdx));
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

        // Теперь ассерт сработает со 100% точностью на 1500 нод
        assertThat(draw.getNodes()).hasSize(nodesCount);

        var startNano = System.nanoTime();
        var graph = draw.init();
        var durationMs = (System.nanoTime() - startNano) / 1_000_000.0;

        System.out.printf("""
            ==================================================
            ❄️ KORA COLD START REALISTIC GRAPH BENCHMARK ❄️
            ==================================================
            🚀 Total Nodes    : %d
            📐 Graph Depth    : %d layers
            ⏱️ Startup Time   : %.2f ms
            ==================================================
            """, draw.size(), layersCount, durationMs);

        assertThat(graph).isNotNull();
        graph.release();
    }
}
