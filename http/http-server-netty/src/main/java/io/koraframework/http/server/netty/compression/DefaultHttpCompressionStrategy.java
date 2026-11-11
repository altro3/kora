package io.koraframework.http.server.netty.compression;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

public final class DefaultHttpCompressionStrategy {

    private final int compressionThreshold;
    private final int compressionLevel;
    private final int maxZstdEncodeSize;

    public DefaultHttpCompressionStrategy(int compressionThreshold, int compressionLevel, int maxZstdEncodeSize) {
        this.compressionThreshold = compressionThreshold;
        this.compressionLevel = compressionLevel;
        this.maxZstdEncodeSize = maxZstdEncodeSize;
    }

    public boolean isEnabled() {
        return compressionThreshold >= 0;
    }

    public boolean shouldCompress(HttpResponse response) {
        if (!isEnabled()) {
            return false;
        }
        return shouldCompress(response, HttpUtil.getContentLength(response, -1));
    }

    public boolean shouldCompress(HttpResponse response, long contentLength) {
        if (!isEnabled()) {
            return false;
        }
        if (contentLength != -1 && contentLength < compressionThreshold) {
            return false;
        }
        HttpHeaders headers = response.headers();
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        return contentType != null && isTextBased(contentType);
    }

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public int getMaxZstdEncodeSize() {
        return maxZstdEncodeSize;
    }

    private static boolean isTextBased(String contentType) {
        String type = contentType.toLowerCase();
        return type.startsWith("text/")
            || type.startsWith("application/json")
            || type.startsWith("application/javascript")
            || type.startsWith("application/xml")
            || type.startsWith("application/xhtml+xml")
            || type.startsWith("image/svg+xml");
    }
}
