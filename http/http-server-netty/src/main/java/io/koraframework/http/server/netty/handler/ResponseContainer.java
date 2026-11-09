package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.telemetry.*;
import io.netty.handler.codec.http.*;

public record ResponseContainer(
    FullHttpRequest nettyReq,
    HttpServerResponse koraResponse,
    HttpServerObservation observation
) {}
