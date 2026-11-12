package io.koraframework.http.server.netty.buffer;

import io.netty.buffer.*;
import org.jspecify.annotations.*;

/**
 * The single owner of a buffer within a scope. The buffer is either handed off once with
 * {@link #take()}, after which the receiver releases it, or released by {@link #close()}. Used
 * with try-with-resources, every path out of the scope releases the buffer exactly once unless it
 * was handed off.
 *
 * <p>Not thread-safe: the owner is confined to the thread of its scope.
 */
public final class OwnedBuffer implements AutoCloseable {
    @Nullable
    private ByteBuf buffer;

    /**
     * @param buffer The buffer, whose ownership transfers to this owner
     */
    public OwnedBuffer(ByteBuf buffer) {
        this.buffer = buffer;
    }

    /**
     * Hand the buffer off. The caller owns it afterward, and {@link #close()} does not release
     * it.
     *
     * @return The buffer
     * @throws IllegalStateException if the buffer was already taken or released
     */
    public ByteBuf take() {
        ByteBuf b = buffer;
        if (b == null) {
            throw new IllegalStateException("Buffer already taken or released");
        }
        buffer = null;
        return b;
    }

    /**
     * Release the buffer, unless it was taken. Further calls do nothing.
     */
    @Override
    public void close() {
        ByteBuf b = buffer;
        if (b != null) {
            buffer = null;
            b.release();
        }
    }
}
