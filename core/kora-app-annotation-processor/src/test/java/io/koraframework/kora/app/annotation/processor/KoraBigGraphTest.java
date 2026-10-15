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
        int nodesCount = 250;  // Компактный реалистичный микросервис
        int layersCount = 5;   // 5 слоев архитектуры

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

            if (layer == layersCount - 1 || (layer > 1 && i % 5 == 0)) {
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
                var propNodes = layerToNodesMap[0]; // Читаем Слой 0 из массива
                deps.add(propNodes.get(i % propNodes.size()));
            } else if (layer == layersCount - 1) {
                // Слой 4 (Controllers): только на нижний слой (Слой 3) и Properties (Слой 0)
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                var prevLayerNodes = layerToNodesMap[layersCount - 2]; // Читаем Слой 3 из массива
                deps.add(prevLayerNodes.get(i % prevLayerNodes.size()));
            } else {
                // Слой 2 и 3 (Services/Components): могут ссылаться на всё, кроме контроллеров
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                var repoNodes = layerToNodesMap[1]; // Читаем Слой 1 из массива
                deps.add(repoNodes.get(i % repoNodes.size()));

                if (layer == 3) {
                    var serviceLayer2Nodes = layerToNodesMap[2]; // Читаем Слой 2 из массива
                    deps.add(serviceLayer2Nodes.get(i % serviceLayer2Nodes.size()));
                }

                // ГОРИЗОНТАЛЬНЫЕ СВЯЗИ внутри слоя (Настоящий граф)
                var currentLayerNodes = layerToNodesMap[layer];
                int internalIdx = currentLayerNodes.indexOf(i);
                if (internalIdx > 1) {
                    deps.add(currentLayerNodes.get(internalIdx - 1));
                }
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
