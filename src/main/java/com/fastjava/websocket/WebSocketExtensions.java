package com.fastjava.websocket;

public final class WebSocketExtensions {

    public static final String PERMESSAGE_DEFLATE_TOKEN = "permessage-deflate";
    private static final String PERMESSAGE_DEFLATE_RESPONSE = "permessage-deflate; server_no_context_takeover; client_no_context_takeover";

    private WebSocketExtensions() {
    }

    public static boolean requestIncludesPerMessageDeflate(String extensionsHeader) {
        if (extensionsHeader == null || extensionsHeader.isBlank()) {
            return false;
        }
        int length = extensionsHeader.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = extensionsHeader.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }

            int segmentStart = tokenStart;
            while (segmentStart < tokenEnd && Character.isWhitespace(extensionsHeader.charAt(segmentStart))) {
                segmentStart++;
            }
            int segmentEnd = tokenEnd;
            while (segmentEnd > segmentStart && Character.isWhitespace(extensionsHeader.charAt(segmentEnd - 1))) {
                segmentEnd--;
            }
            if (segmentStart < segmentEnd) {
                int semicolon = extensionsHeader.indexOf(';', segmentStart);
                int valueEnd = semicolon >= 0 && semicolon < segmentEnd ? semicolon : segmentEnd;
                while (valueEnd > segmentStart && Character.isWhitespace(extensionsHeader.charAt(valueEnd - 1))) {
                    valueEnd--;
                }
                if (equalsIgnoreCaseRange(extensionsHeader, segmentStart, valueEnd, PERMESSAGE_DEFLATE_TOKEN)) {
                    return true;
                }
            }

            tokenStart = tokenEnd + 1;
        }
        return false;
    }

    private static boolean equalsIgnoreCaseRange(String source, int start, int end, String expected) {
        int length = end - start;
        if (length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (Character.toLowerCase(source.charAt(start + i)) != Character.toLowerCase(expected.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String perMessageDeflateResponseHeader(boolean enabled) {
        return enabled ? PERMESSAGE_DEFLATE_RESPONSE : null;
    }
}