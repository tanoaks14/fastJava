package com.fastjava.server.config;

/**
 * HTTP/2 feature flags and safety limits.
 */
public record Http2Config(
        boolean enabled,
        boolean h2cEnabled,
        int maxConcurrentStreams,
        int initialWindowSize,
        int maxFrameSize,
        int headerTableSize,
        int maxHeaderListSize,
        boolean strictAlpn) {

    public static final int DEFAULT_MAX_CONCURRENT_STREAMS = 100;
    public static final int DEFAULT_INITIAL_WINDOW_SIZE = 65_535;
    public static final int DEFAULT_MAX_FRAME_SIZE = 16_384;
    public static final int DEFAULT_HEADER_TABLE_SIZE = 4_096;
    public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 32_768;

    public static Http2Config defaults() {
        return new Http2Config(
                false,
                false,
                DEFAULT_MAX_CONCURRENT_STREAMS,
                DEFAULT_INITIAL_WINDOW_SIZE,
                DEFAULT_MAX_FRAME_SIZE,
                DEFAULT_HEADER_TABLE_SIZE,
                DEFAULT_MAX_HEADER_LIST_SIZE,
                false);
    }
}