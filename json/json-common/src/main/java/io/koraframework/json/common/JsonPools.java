package io.koraframework.json.common;

import com.alibaba.fastjson2.*;

import java.util.concurrent.*;

public final class JsonPools {
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final ConcurrentLinkedQueue<byte[]> BYTE_BUF_POOL = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<JSONReader.Context> READER_CONTEXT_POOL = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<JSONWriter.Context> WRITER_CONTEXT_POOL = new ConcurrentLinkedQueue<>();

    public static byte[] borrowBuffer() {
        byte[] buf = BYTE_BUF_POOL.poll();
        if (buf == null) {
            return new byte[BUFFER_SIZE];
        }
        return buf;
    }

    public static void returnBuffer(byte[] buf) {
        if (buf != null && buf.length == BUFFER_SIZE) {
            if (BYTE_BUF_POOL.size() < 1024) {
                BYTE_BUF_POOL.offer(buf);
            }
        }
    }

    public static JSONReader.Context borrowReaderContext() {
        JSONReader.Context context = READER_CONTEXT_POOL.poll();
        if (context == null) {
            return new JSONReader.Context(JSONFactory.getDefaultObjectReaderProvider());
        }
        return context;
    }

    public static void returnReaderContext(JSONReader.Context context) {
        if (context != null && READER_CONTEXT_POOL.size() < 1024) {
            READER_CONTEXT_POOL.offer(context);
        }
    }

    public static JSONWriter.Context borrowWriterContext() {
        JSONWriter.Context context = WRITER_CONTEXT_POOL.poll();
        if (context == null) {
            return new JSONWriter.Context(JSONFactory.getDefaultObjectWriterProvider());
        }
        return context;
    }

    public static void returnWriterContext(JSONWriter.Context context) {
        if (context != null && WRITER_CONTEXT_POOL.size() < 1024) {
            WRITER_CONTEXT_POOL.offer(context);
        }
    }
}
