package io.koraframework.http.server.netty.buffer;

import io.netty.buffer.*;
import io.netty.util.*;
import org.jspecify.annotations.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public final class KoraNettyBufferFactory {

    public static final int MAX_COMPOSITE_COMPONENTS = 4096;

    private final ByteBufAllocator allocator;

    public KoraNettyBufferFactory(ByteBufAllocator allocator) {
        this.allocator = allocator;
    }

    public KoraReadBuffer createEmpty() {
        return adapt(Unpooled.EMPTY_BUFFER);
    }

    public KoraReadBuffer copyOf(CharSequence cs, Charset charset) {
        ByteBuf byteBuf = charset == StandardCharsets.UTF_8 ?
            ByteBufUtil.writeUtf8(allocator, cs) :
            ByteBufUtil.encodeString(allocator, CharBuffer.wrap(cs), charset);
        return adapt(byteBuf);
    }

    public KoraReadBuffer copyOf(InputStream stream) throws IOException {
        ByteBuf buffer = allocator.buffer();
        boolean free = true;
        try {
            while (true) {
                if (buffer.writeBytes(stream, 4096) == -1) {
                    break;
                }
            }
            free = false;
            return adapt(buffer);
        } finally {
            if (free) {
                buffer.release();
            }
        }
    }

    @Nullable
    public KoraReadBuffer copyOf(ScatteringByteChannel channel, int n) throws IOException {
        ByteBuf bb = allocator.buffer(n);
        int actual;
        try {
            actual = bb.writeBytes(channel, n);
        } catch (Throwable e) {
            bb.release();
            throw e;
        }
        if (actual < 0) {
            bb.release();
            return null;
        } else if (actual > 0) {
            return adapt(bb);
        } else {
            bb.release();
            return createEmpty();
        }
    }

    public KoraReadBuffer copyOf(ByteBuffer nioBuffer) {
        ByteBuf bb = allocator.buffer(nioBuffer.remaining());
        boolean done = false;
        try {
            bb.writeBytes(nioBuffer);
            done = true;
            return adapt(bb);
        } finally {
            if (!done) {
                bb.release();
            }
        }
    }

    public KoraReadBuffer adapt(ByteBuffer nioBuffer) {
        return adapt(Unpooled.wrappedBuffer(nioBuffer));
    }

    public KoraReadBuffer adapt(byte[] array) {
        return adapt(Unpooled.wrappedBuffer(array));
    }

    public KoraReadBuffer adapt(ByteBuf buffer) {
        return new KoraReadBuffer(buffer);
    }

    public static ByteBuf toByteBuf(KoraReadBuffer readBuffer) {
        return readBuffer.toByteBuf();
    }

    public KoraReadBuffer compose(Iterable<KoraReadBuffer> buffers) {
        var itr = buffers.iterator();
        if (!itr.hasNext()) {
            return createEmpty();
        } else {
            KoraReadBuffer first = itr.next();
            if (!itr.hasNext()) {
                return first;
            }
        }

        List<ByteBuf> components = buffers instanceof Collection<?> collection
            ? new ArrayList<>(collection.size())
            : new ArrayList<>();
        try {
            for (KoraReadBuffer buffer : buffers) {
                components.add(toByteBuf(buffer));
            }
        } catch (Throwable e) {
            for (ByteBuf component : components) {
                ReferenceCountUtil.safeRelease(component);
            }
            for (KoraReadBuffer buffer : buffers) {
                try {
                    buffer.close();
                } catch (Throwable f) {
                    e.addSuppressed(f);
                }
            }
            throw e;
        }

        CompositeByteBuf composite = allocator.compositeBuffer(MAX_COMPOSITE_COMPONENTS);
        try {
            composite.addComponents(true, components);
            return adapt(composite);
        } catch (Throwable e) {
            composite.release();
            throw e;
        }
    }
}
