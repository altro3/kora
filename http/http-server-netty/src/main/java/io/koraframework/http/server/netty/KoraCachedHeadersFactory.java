package io.koraframework.http.server.netty;

import io.netty.handler.codec.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

import java.util.*;

public final class KoraCachedHeadersFactory {
    public static DefaultHttpHeadersFactory build() {
        return DefaultHttpHeadersFactory.headersFactory()
            .withNameValidation(false)
            .withValueValidation(false);
    }
}
