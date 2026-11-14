package io.koraframework.json.common;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.reader.*;
import com.alibaba.fastjson2.writer.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class JsonPools {
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int MAX_POOL_SIZE = 1024;

    private static final ObjectReaderProvider DEFAULT_READER_PROVIDER = JSONFactory.getDefaultObjectReaderProvider();
    private static final ObjectWriterProvider DEFAULT_WRITER_PROVIDER = JSONFactory.getDefaultObjectWriterProvider();

    private static final ConcurrentLinkedQueue<byte[]> BYTE_BUF_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger BYTE_BUF_POOL_SIZE = new AtomicInteger();

    private static final ConcurrentLinkedQueue<JSONReader.Context> READER_CONTEXT_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger READER_CONTEXT_POOL_SIZE = new AtomicInteger();

    private static final ConcurrentLinkedQueue<JSONWriter.Context> WRITER_CONTEXT_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger WRITER_CONTEXT_POOL_SIZE = new AtomicInteger();

    public static byte[] borrowBuffer() {
        byte[] buf = BYTE_BUF_POOL.poll();
        if (buf == null) {
            return new byte[BUFFER_SIZE];
        }
        BYTE_BUF_POOL_SIZE.decrementAndGet();
        return buf;
    }

    public static void returnBuffer(byte[] buf) {
        if (buf != null && buf.length == BUFFER_SIZE && BYTE_BUF_POOL_SIZE.get() < MAX_POOL_SIZE) {
            BYTE_BUF_POOL_SIZE.incrementAndGet();
            BYTE_BUF_POOL.offer(buf);
        }
    }

    public static JSONReader.Context borrowReaderContext() {
        JSONReader.Context context = READER_CONTEXT_POOL.poll();
        if (context == null) {
            return new JSONReader.Context(DEFAULT_READER_PROVIDER);
        }
        READER_CONTEXT_POOL_SIZE.decrementAndGet();
        return context;
    }

    public static void returnReaderContext(JSONReader.Context context) {
        if (context != null && READER_CONTEXT_POOL_SIZE.get() < MAX_POOL_SIZE) {
            READER_CONTEXT_POOL_SIZE.incrementAndGet();
            READER_CONTEXT_POOL.offer(context);
        }
    }

    public static JSONWriter.Context borrowWriterContext() {
        JSONWriter.Context context = WRITER_CONTEXT_POOL.poll();
        if (context == null) {
            return new JSONWriter.Context(DEFAULT_WRITER_PROVIDER);
        }
        WRITER_CONTEXT_POOL_SIZE.decrementAndGet();
        return context;
    }

    public static void returnWriterContext(JSONWriter.Context context) {
        if (context != null && WRITER_CONTEXT_POOL_SIZE.get() < MAX_POOL_SIZE) {
            WRITER_CONTEXT_POOL_SIZE.incrementAndGet();
            WRITER_CONTEXT_POOL.offer(context);
        }
    }
}
