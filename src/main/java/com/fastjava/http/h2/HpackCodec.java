package com.fastjava.http.h2;

import com.fastjava.http.simd.SIMDByteScanner;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;
import com.twitter.hpack.HeaderListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HpackCodec {

    private static final boolean USE_SIMD_HEADER_NORMALIZATION = Boolean
            .parseBoolean(System.getProperty("fastjava.http2.hpack.simdNormalization", "true"));

    private final Decoder decoder;
    private final Encoder encoder;

    public HpackCodec(int maxHeaderListSize, int headerTableSize) {
        this.decoder = new Decoder(maxHeaderListSize, headerTableSize);
        this.encoder = new Encoder(headerTableSize);
    }

    public Map<String, String> decode(byte[] headerBlock) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        HeaderListener listener = (name, value, sensitive) -> {
            String headerName = decodeHeaderName(name);
            String headerValue = new String(value, StandardCharsets.UTF_8);
            headers.put(headerName, headerValue);
        };
        decoder.decode(new ByteArrayInputStream(headerBlock), listener);
        decoder.endHeaderBlock();
        return headers;
    }

    public byte[] encode(Map<String, String> headers) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            byte[] name = encodeHeaderName(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue();
            encoder.encodeHeader(
                    output,
                    name,
                    value.getBytes(StandardCharsets.UTF_8),
                    false);
        }
        return output.toByteArray();
    }

    private static String decodeHeaderName(byte[] name) {
        if (!USE_SIMD_HEADER_NORMALIZATION) {
            return new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        }
        byte[] normalizedName = normalizeLowercaseAscii(name);
        return new String(normalizedName, StandardCharsets.US_ASCII);
    }

    private static byte[] encodeHeaderName(String headerName) {
        if (!USE_SIMD_HEADER_NORMALIZATION) {
            return headerName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        }
        return normalizeLowercaseAscii(headerName.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] normalizeLowercaseAscii(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        if (!containsUppercaseAscii(input)) {
            return input;
        }
        return SIMDByteScanner.toLowercaseAscii(input, 0, input.length);
    }

    private static boolean containsUppercaseAscii(byte[] bytes) {
        for (byte value : bytes) {
            if (value >= 'A' && value <= 'Z') {
                return true;
            }
        }
        return false;
    }
}