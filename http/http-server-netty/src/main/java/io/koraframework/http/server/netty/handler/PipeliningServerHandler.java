package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.buffer.*;
import io.koraframework.http.server.netty.compression.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

public final class PipeliningServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(PipeliningServerHandler.class);

    static final int MAX_COMPOSITE_COMPONENTS = 4096;
    private static final long LINGER_IDLE_MILLIS = 5_000;

    final RequestHandler requestHandler;
    final KoraDroppingInboundHandler droppingInboundHandler = new KoraDroppingInboundHandler(this);
    final KoraDroppingInboundHandler noBodyInboundHandler = new KoraDroppingInboundHandler(this);
    private final InboundHandler baseInboundHandler = new KoraMessageInboundHandler(this);
    private final KoraOptimisticBufferingInboundHandler optimisticBufferingInboundHandler = new KoraOptimisticBufferingInboundHandler(this);

    @Nullable Compressor compressor;
    BodySizeLimits bodySizeLimits = BodySizeLimits.UNLIMITED;
    boolean requestDecompressionEnabled = true;
    boolean http10KeepAlive;
    boolean rejectUnsupportedHttpVersions;

    private InboundHandler inboundHandler = baseInboundHandler;
    final Deque<KoraOutboundAccess> outboundQueue = new ArrayDeque<>(1);
    @Nullable OutboundHandler outboundHandler = null;

    @Nullable
    private ChannelHandlerContext ctx;
    private boolean reading = false;
    private boolean readCalled = false;
    boolean removed = false;
    private boolean flushPending = false;
    private boolean writing = false;
    boolean shuttingDown = false;

    private @Nullable KoraNettyBufferFactory bufferFactory;

    public PipeliningServerHandler(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public ChannelHandlerContext requiredCtx() {
        return Objects.requireNonNull(ctx, "ctx");
    }

    public KoraNettyBufferFactory bufferFactory() {
        var factory = bufferFactory;
        if (factory == null) {
            factory = new KoraNettyBufferFactory(requiredCtx().alloc());
            bufferFactory = factory;
        }
        return factory;
    }

    public InboundHandler baseInboundHandler() {
        return baseInboundHandler;
    }

    public KoraOptimisticBufferingInboundHandler optimisticBufferingInboundHandler() {
        return optimisticBufferingInboundHandler;
    }

    public InboundHandler inboundHandler() {
        return inboundHandler;
    }

    public void setInboundHandler(InboundHandler inboundHandler) {
        this.inboundHandler = inboundHandler;
    }

    public void setOutboundHandler(@Nullable OutboundHandler outboundHandler) {
        this.outboundHandler = outboundHandler;
    }

    public void setCompressionStrategy(DefaultHttpCompressionStrategy compressionStrategy) {
        compressor = compressionStrategy.isEnabled() ? new Compressor(compressionStrategy) : null;
    }

    public void setBodySizeLimits(BodySizeLimits bodySizeLimits) {
        this.bodySizeLimits = bodySizeLimits;
    }

    public void setRequestDecompressionEnabled(boolean requestDecompressionEnabled) {
        this.requestDecompressionEnabled = requestDecompressionEnabled;
    }

    public void setHttp10KeepAlive(boolean http10KeepAlive) {
        this.http10KeepAlive = http10KeepAlive;
    }

    public void setRejectUnsupportedHttpVersions(boolean rejectUnsupportedHttpVersions) {
        this.rejectUnsupportedHttpVersions = rejectUnsupportedHttpVersions;
    }

    public boolean canHaveBody(HttpResponseStatus status) {
        int code = status.code();
        return !(code >= 100 && code < 200 || code == 204 || code == 304);
    }

    void accept(HttpRequest request, @Nullable AutoCloseable body, KoraOutboundAccess outboundAccess) {
        try {
            requestHandler.accept(requiredCtx(), request, body, outboundAccess);
        } catch (Exception e) {
            if (body != null) {
                try {
                    body.close();
                } catch (Exception closeError) {
                    e.addSuppressed(closeError);
                }
            }
            try {
                requestHandler.handleUnboundError(e);
            } catch (RuntimeException handlerError) {
                LOG.debug("Failed to handle an error from the request handler", handlerError);
            }
            rejectUnaccepted(outboundAccess);
        }
    }

    void rejectUnaccepted(KoraOutboundAccess outboundAccess) {
        if (outboundAccess.responseStarted()) {
            if (outboundQueue.contains(outboundAccess) || (outboundHandler != null && outboundHandler.outboundAccess == outboundAccess)) {
                outboundAccess.closeAfterWriteInEventLoop();
            } else {
                requiredCtx().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        try {
            outboundAccess.closeAfterWrite();
            var errResponse = new DefaultHttpResponse(outboundAccess.request().protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
            outboundAccess.write(errResponse, (KoraAvailableBody) null);
        } catch (RuntimeException e) {
            LOG.debug("Failed to write the error response for a request that was not accepted", e);
            if (!outboundAccess.responseStarted()) {
                requestHandler.responseWritten(outboundAccess.attachment());
            }
            requiredCtx().close();
        }
    }

    boolean hasBody(HttpRequest request) {
        if (request.decoderResult().isFailure()) {
            return false;
        }
        int contentLength;
        try {
            contentLength = HttpUtil.getContentLength(request, 0);
        } catch (NumberFormatException e) {
            contentLength = 0;
        }
        return contentLength != 0 || HttpUtil.isTransferEncodingChunked(request);
    }

    void refreshNeedMore() {
        assert ctx != null;
        if (!readCalled && outboundQueue.size() <= 1 && inboundHandler.needMore()) {
            readCalled = true;
            requiredCtx().read();
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        ctx.channel().config().setAutoRead(false);
        refreshNeedMore();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        removed = true;
        if (outboundHandler != null) {
            outboundHandler.discardOutbound();
        }
        for (var queued : outboundQueue) {
            if (queued.handler() != null) {
                queued.handler().discardOutbound();
            }
        }
        inboundHandler.discard();
        outboundQueue.clear();
        requestHandler.removed();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        reading = true;
        inboundHandler.read(msg);
    }

    void closeAfterContentDecoderFailure() {
        var lastResponse = outboundQueue.peekLast();
        if (lastResponse == null && outboundHandler != null) {
            lastResponse = outboundHandler.outboundAccess;
        }
        if (lastResponse == null) {
            requiredCtx().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        } else {
            lastResponse.closeAfterWriteInEventLoop();
        }
    }

    void handleContentDecoderFailure(HttpContent content) {
        var cause = content.decoderResult().cause();
        content.release();
        closeAfterContentDecoderFailure();
        inboundHandler.handleUpstreamError(cause);
        inboundHandler.read(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        inboundHandler.readComplete();
        reading = false;
        readCalled = false;
        if (flushPending) {
            ctx.flush();
            flushPending = false;
        }
        refreshNeedMore();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        inboundHandler.handleUpstreamError(cause);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        writeSome();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent && idleStateEvent.state() == IdleState.ALL_IDLE) {
            ctx.close();
        }
        super.userEventTriggered(ctx, evt);
    }

    public ChannelFuture write0(Object message, boolean flush, boolean close, boolean needsPromise, @Nullable ChannelPromise given) {
        if (close) {
            var promise = given == null ? requiredCtx().newPromise() : given;
            return requiredCtx().writeAndFlush(message, promise)
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        closeAfterResponse();
                    } else {
                        future.channel().close();
                    }
                });
        } else {
            ChannelPromise promise;
            if (given != null) {
                promise = given.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            } else {
                promise = needsPromise
                    ? requiredCtx().newPromise().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
                    : requiredCtx().voidPromise();
            }
            var future = requiredCtx().write(message, promise);
            if (flush) {
                if (reading) {
                    flushPending = true;
                } else {
                    requiredCtx().flush();
                }
            }
            return future;
        }
    }

    void writeSome() {
        assert ctx != null;
        if (writing) {
            return;
        }
        writing = true;
        try {
            while (requiredCtx().channel().isWritable()) {
                if (outboundHandler == null) {
                    var next = outboundQueue.peek();
                    if (next != null && next.handler() != null) {
                        outboundQueue.poll();
                        outboundHandler = next.handler();
                        refreshNeedMore();
                    } else {
                        return;
                    }
                }
                var oldHandler = outboundHandler;
                oldHandler.writeSome();
                if (outboundHandler == oldHandler) {
                    break;
                }
            }
        } finally {
            writing = false;
        }
    }

    @SuppressWarnings("resource")
    public CompletionStage<?> shutdownGracefully() {
        assert ctx != null;
        if (requiredCtx().executor().inEventLoop()) {
            shutdownGracefully0();
        } else {
            requiredCtx().executor().execute(this::shutdownGracefully0);
        }
        var future = new CompletableFuture();
        requiredCtx().channel().closeFuture().addListener(f -> future.complete(null));
        return future;
    }

    private void shutdownGracefully0() {
        assert ctx != null;
        shuttingDown = true;
        if (inboundHandler == baseInboundHandler && outboundHandler == null && outboundQueue.isEmpty()) {
            requiredCtx().close();
        } else {
            var lastResponse = outboundQueue.peekLast();
            if (lastResponse == null && outboundHandler != null) {
                lastResponse = outboundHandler.outboundAccess;
            }
            if (lastResponse != null) {
                lastResponse.closeAfterWriteInEventLoop();
            }
        }
    }

    static ByteBuf composeBody(ByteBufAllocator alloc, List<ByteBuf> pieces) {
        var composite = alloc.compositeBuffer(MAX_COMPOSITE_COMPONENTS);
        try (var owned = new OwnedBuffer(composite)) {
            composite.addComponents(true, pieces);
            return owned.take();
        }
    }

    private void closeAfterResponse() {
        var context = requiredCtx();
        if (inboundHandler == baseInboundHandler || inboundHandler == noBodyInboundHandler) {
            inboundHandler = new KoraClosedInboundHandler();
            context.close();
            return;
        }
        if (removed || !(context.channel() instanceof DuplexChannel channel) || !channel.isActive()) {
            context.close();
            return;
        }
        inboundHandler.discard();
        var lingering = new KoraLingeringInboundHandler(context, this);
        inboundHandler = lingering;
        lingering.scheduleTimeout(LINGER_IDLE_MILLIS);
        channel.closeFuture().addListener(f -> lingering.cancelTimeout());
        var ssl = context.pipeline().get(SslHandler.class);
        if (ssl == null) {
            channel.shutdownOutput();
        } else {
            ssl.closeOutbound().addListener(f -> channel.shutdownOutput());
        }
        refreshNeedMore();
    }
}
