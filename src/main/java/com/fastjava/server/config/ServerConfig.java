package com.fastjava.server.config;

import com.fastjava.server.RequestLimits;

public record ServerConfig(int port, int threads, RequestLimits requestLimits, Http2Config http2Config) {

    public ServerConfig(int port, int threads, RequestLimits requestLimits) {
        this(port, threads, requestLimits, Http2Config.defaults());
    }

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_THREADS = 16;
    public static final int DEFAULT_MAX_REQUEST_SIZE = 16_384;

    public static ServerConfig defaults() {
        return new ServerConfig(
                DEFAULT_PORT,
                DEFAULT_THREADS,
                RequestLimits.defaults(DEFAULT_MAX_REQUEST_SIZE),
                Http2Config.defaults());
    }
}
