package io.koraframework.json.common;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONReader;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class JsonPools {
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final ConcurrentLinkedQueue<byte[]> BYTE_BUF_POOL = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<JSONReader.Context> CONTEXT_POOL = new ConcurrentLinkedQueue<>();

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

    public static JSONReader.Context borrowContext() {
        JSONReader.Context context = CONTEXT_POOL.poll();
        if (context == null) {
            return new JSONReader.Context(JSONFactory.getDefaultObjectReaderProvider());
        }
        return context;
    }

    public static void returnContext(JSONReader.Context context) {
        if (context != null && CONTEXT_POOL.size() < 1024) {
            CONTEXT_POOL.offer(context);
        }
    }
}
