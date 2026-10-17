package io.koraframework.kora.app.annotation.processor;

import io.koraframework.application.graph.*;
import io.koraframework.application.graph.internal.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;

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
    public void testColdStart() throws Exception {
        int nodesCount = 1500;
        var sb = new StringBuilder("\n")
            .append("@KoraApp\n")
            .append("public interface ExampleApplication {\n");
        for (int i = 0; i < nodesCount; i++) {
            sb.append("  @Root\n");
            sb.append("  default String component").append(i).append("() { return \"\"; }\n");
        }
        sb.append("}\n");

        var draw = compile(sb.toString());
        assertThat(draw.getNodes()).hasSize(nodesCount);

        var startNano = System.nanoTime();
        var graph = draw.init();
        var durationMs = (System.nanoTime() - startNano) / 1_000_000.0;

        System.out.printf("""
            ==================================================
            ❄️ KORA COLD START GRAPH BENCHMARK ❄️
            ==================================================
            🚀 Nodes Count   : %d
            ⏱️ Startup Time : %.2f ms
            ==================================================
            """, draw.size(), durationMs);

        assertThat(graph).isNotNull();
    }

    @Test
    public void testRealisticEnterpriseGraphColdStart() throws Exception {
        int nodesCount = 250;
        int layersCount = 5;

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

        for (int i = 0; i < nodesCount; i++) {
            sb.append("  interface Comp").append(i).append(" {}\n");
        }

        for (int i = 0; i < nodesCount; i++) {
            int layer = nodeToLayerMap[i];

            if (layer == layersCount - 1 || i == nodesCount - 1) {
                sb.append("  @Root\n");
            }

            sb.append("  @Tag(Comp").append(i).append(".class)\n");
            sb.append("  default String component").append(i).append("(");

            java.util.List<Integer> deps = new java.util.ArrayList<>();

            if (layer == 0) {
            } else if (layer == 1) {
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));
            } else if (layer == layersCount - 1) {
                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));

                deps.add(i - 1);
            } else {
                deps.add(i - 1);

                var propNodes = layerToNodesMap[0];
                deps.add(propNodes.get(i % propNodes.size()));
                var repoNodes = layerToNodesMap[1];
                deps.add(repoNodes.get(i % repoNodes.size()));
            }

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

    @Test
    public void testPureRealisticGraphDrawBuildTime() {
        int nodesCount = 2500;
        int layersCount = 5;

        int nodesPerLayer = nodesCount / layersCount;
        int[] nodeToLayerMap = new int[nodesCount];
        List<Integer>[] layerToNodesMap = new List[layersCount];
        for (int i = 0; i < layersCount; i++) {
            layerToNodesMap[i] = new ArrayList<>();
        }

        int currentLayer = 0;
        for (int i = 0; i < nodesCount; i++) {
            if (currentLayer < layersCount - 1 && layerToNodesMap[currentLayer].size() >= nodesPerLayer) {
                currentLayer++;
            }
            nodeToLayerMap[i] = currentLayer;
            layerToNodesMap[currentLayer].add(i);
        }

        var startPureNano = System.nanoTime();

        var draw = new ApplicationGraphDraw(ExampleApplication.class);
        List<Node<?>> allNodes = new ArrayList<>(nodesCount);

        for (int i = 0; i < nodesCount; i++) {
            int layer = nodeToLayerMap[i];
            List<Node<?>> deps = new ArrayList<>();

            if (layer == 0) {
            } else if (layer == 1) {
                var propNodes = layerToNodesMap[0];
                deps.add(allNodes.get(propNodes.get(i % propNodes.size())));
            } else if (layer == layersCount - 1) {
                var propNodes = layerToNodesMap[0];
                deps.add(allNodes.get(propNodes.get(i % propNodes.size())));
                deps.add(allNodes.get(i - 1));
            } else {
                deps.add(allNodes.get(i - 1));
                var propNodes = layerToNodesMap[0];
                deps.add(allNodes.get(propNodes.get(i % propNodes.size())));
                var repoNodes = layerToNodesMap[1];
                deps.add(allNodes.get(repoNodes.get(i % repoNodes.size())));
            }

            Type fakeType = new FakeType(i);
            Class<?> fakeTag = FakeTag.class;

            var node = draw.addNode(fakeType, fakeTag, null, deps, List.of(), List.of(), graph -> "");
            allNodes.add(node);

            draw.findNodeByType(fakeType);
        }

        var totalPureNano = System.nanoTime() - startPureNano;

        var durationMs = totalPureNano / 1_000_000.0;
        var avgPerNodeNano = totalPureNano / nodesCount;

        System.out.printf("""
            ==================================================
            🎯 PURE KORA GRAPH DRAW BENCHMARK (NO JAVAC) 🎯
            ==================================================
            🚀 Total Nodes        : %d
            📐 Graph Depth        : %d layers
            ⏱️ Clean Draw Time    : %.4f ms
            📊 Avg Time per Node  : %d ns
            ==================================================
            """, draw.size(), layersCount, durationMs, avgPerNodeNano);

        assertThat(draw.getNodes()).hasSize(nodesCount);
    }

    private interface ExampleApplication {}
    private interface FakeTag {}
    private record FakeType(int id) implements Type {
        @Override public String getTypeName() { return "Comp" + id; }
    }
}
