package io.koraframework.json.common;

import com.alibaba.fastjson2.*;

import java.lang.ref.SoftReference;

public final class JsonPools {
    private static final int BUFFER_SIZE = 8 * 1024;

    private static final ThreadLocal<SoftReference<byte[]>> THREAD_LOCAL_POOL = new ThreadLocal<>();

    public static byte[] borrowThreadLocal() {
        var ref = THREAD_LOCAL_POOL.get();
        if (ref == null) {
            var buf = new byte[BUFFER_SIZE];
            THREAD_LOCAL_POOL.set(new SoftReference<>(buf));
            return buf;
        }

        byte[] buf = ref.get();
        if (buf == null) {
            buf = new byte[BUFFER_SIZE];
            THREAD_LOCAL_POOL.set(new SoftReference<>(buf));
        }
        return buf;
    }

    public static void returnThreadLocal(byte[] buf) {
        if (buf.length == BUFFER_SIZE) {
            return;
        }
        if (buf.length > BUFFER_SIZE) {
            THREAD_LOCAL_POOL.set(new SoftReference<>(new byte[BUFFER_SIZE]));
        }
    }
}
