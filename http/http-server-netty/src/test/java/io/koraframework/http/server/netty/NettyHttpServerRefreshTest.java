package io.koraframework.http.server.netty;

import io.koraframework.application.graph.ValueOf;
import io.koraframework.http.server.common.$HttpServerConfig_ConfigValueMapper;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.telemetry.HttpServerTelemetryConfig;
import io.koraframework.http.server.netty.handler.NettyHttpHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NettyHttpServerRefreshTest {

    private static NettyResourceLifecycle.NettyResources resources;

    @BeforeAll
    static void setup() {
        resources = new NettyResourceLifecycle.NettyResources(
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()),
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()),
            NioServerSocketChannel.class
        );
    }

    @AfterAll
    static void teardown() {
        if (resources != null) {
            resources.bossGroup().shutdownGracefully();
            resources.workerGroup().shutdownGracefully();
        }
    }

    @Test
    void servesRequestsWithHandlerReplacedByGraphRefresh() throws Exception {
        var handler = new AtomicReference<NettyHttpHandler>((ctx, _) ->
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(418))));
        var handlerValue = (ValueOf<NettyHttpHandler>) handler::get;
        var server = new NettyHttpServer("test", resources, config(), handlerValue, null);

        server.init();
        try {
            assertThat(status(server.port())).isEqualTo(418);

            handler.set((ctx, _) ->
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(204))));

            assertThat(status(server.port())).isEqualTo(204);
        } finally {
            server.release();
        }
    }

    private static ValueOf<HttpServerConfig> config() {
        var telemetry = $HttpServerConfig_ConfigValueMapper.DEFAULTS.telemetry();
        var config = new HttpServerConfig() {
            @Override
            public int port() {
                return 0;
            }

            @Override
            public HttpServerTelemetryConfig telemetry() {
                return telemetry;
            }
        };
        return () -> config;
    }

    private static int status(int port) throws IOException {
        var connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/").toURL().openConnection();
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
