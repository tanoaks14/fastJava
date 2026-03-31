package com.fastjava.server.config;

import com.fastjava.server.RequestLimits;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class ServerConfigLoader {

    private static final Set<String> SUPPORTED_KEYS = new HashSet<>(Arrays.asList(
            "server.port",
            "server.threads",
            "request.maxRequestSize",
            "request.keepAliveTimeoutMillis",
            "request.readTimeoutMillis",
            "request.maxRequestLineBytes",
            "request.maxHeaderBytes",
            "request.maxBodyBytes",
            "request.maxChunkSizeBytes",
            "request.maxChunkCount",
            "request.writeTimeoutMillis",
            "request.maxMultipartBytes",
            "request.maxMultipartPartBytes",
            "request.multipartMemoryThresholdBytes",
            "request.maxConcurrentConnections",
            "request.keepAlivePressureQueueThreshold",
            "http2.enabled",
            "http2.h2cEnabled",
            "http2.maxConcurrentStreams",
            "http2.initialWindowSize",
            "http2.maxFrameSize",
            "http2.headerTableSize",
            "http2.maxHeaderListSize",
            "http2.strictAlpn"));

    private ServerConfigLoader() {
    }

    public static ServerConfig load(Path configPath) throws IOException {
        ServerConfig defaults = ServerConfig.defaults();
        Properties properties = loadProperties(configPath);

        validateKeys(properties);

        int port = parseIntInRange(properties, "server.port", defaults.port(), 0, 65_535);
        int threads = parseIntInRange(properties, "server.threads", defaults.threads(), 1, 10_000);

        RequestLimits baseLimits = defaults.requestLimits();
        int maxRequestSize = parseIntInRange(properties, "request.maxRequestSize", baseLimits.maxRequestSize(), 1,
                Integer.MAX_VALUE);
        int keepAliveTimeoutMillis = parseIntInRange(properties, "request.keepAliveTimeoutMillis",
                baseLimits.keepAliveTimeoutMillis(), 1, Integer.MAX_VALUE);
        int readTimeoutMillis = parseIntInRange(properties, "request.readTimeoutMillis",
                baseLimits.readTimeoutMillis(), 1, Integer.MAX_VALUE);
        int maxRequestLineBytes = parseIntInRange(properties, "request.maxRequestLineBytes",
                baseLimits.maxRequestLineBytes(), 1, Integer.MAX_VALUE);
        int maxHeaderBytes = parseIntInRange(properties, "request.maxHeaderBytes", baseLimits.maxHeaderBytes(), 1,
                Integer.MAX_VALUE);
        int maxBodyBytes = parseIntInRange(properties, "request.maxBodyBytes", baseLimits.maxBodyBytes(), 1,
                Integer.MAX_VALUE);
        int maxChunkSizeBytes = parseIntInRange(properties, "request.maxChunkSizeBytes", baseLimits.maxChunkSizeBytes(),
                1, Integer.MAX_VALUE);
        int maxChunkCount = parseIntInRange(properties, "request.maxChunkCount", baseLimits.maxChunkCount(), 1,
                Integer.MAX_VALUE);
        int writeTimeoutMillis = parseIntInRange(properties, "request.writeTimeoutMillis",
                baseLimits.writeTimeoutMillis(), 1, Integer.MAX_VALUE);
        int maxMultipartBytes = parseIntInRange(properties, "request.maxMultipartBytes", baseLimits.maxMultipartBytes(),
                1, Integer.MAX_VALUE);
        int maxMultipartPartBytes = parseIntInRange(properties, "request.maxMultipartPartBytes",
                baseLimits.maxMultipartPartBytes(), 1, Integer.MAX_VALUE);
        int multipartMemoryThresholdBytes = parseIntInRange(properties, "request.multipartMemoryThresholdBytes",
                baseLimits.multipartMemoryThresholdBytes(), 1, Integer.MAX_VALUE);
        int maxConcurrentConnections = parseIntInRange(properties, "request.maxConcurrentConnections",
                baseLimits.maxConcurrentConnections(), 1, Integer.MAX_VALUE);
        int keepAlivePressureQueueThreshold = parseIntInRange(properties,
                "request.keepAlivePressureQueueThreshold",
                baseLimits.keepAlivePressureQueueThreshold(), 0, Integer.MAX_VALUE);

        RequestLimits requestLimits = new RequestLimits(
                maxRequestSize,
                keepAliveTimeoutMillis,
                readTimeoutMillis,
                maxRequestLineBytes,
                maxHeaderBytes,
                maxBodyBytes,
                maxChunkSizeBytes,
                maxChunkCount,
                writeTimeoutMillis,
                maxMultipartBytes,
                maxMultipartPartBytes,
                multipartMemoryThresholdBytes,
                maxConcurrentConnections,
                keepAlivePressureQueueThreshold);

        Http2Config defaultHttp2 = defaults.http2Config();
        boolean http2Enabled = parseBoolean(properties, "http2.enabled", defaultHttp2.enabled());
        boolean h2cEnabled = parseBoolean(properties, "http2.h2cEnabled", defaultHttp2.h2cEnabled());
        int maxConcurrentStreams = parseIntInRange(properties,
                "http2.maxConcurrentStreams",
                defaultHttp2.maxConcurrentStreams(),
                1,
                Integer.MAX_VALUE);
        int initialWindowSize = parseIntInRange(properties,
                "http2.initialWindowSize",
                defaultHttp2.initialWindowSize(),
                0,
                Integer.MAX_VALUE);
        int maxFrameSize = parseIntInRange(properties,
                "http2.maxFrameSize",
                defaultHttp2.maxFrameSize(),
                16_384,
                16_777_215);
        int headerTableSize = parseIntInRange(properties,
                "http2.headerTableSize",
                defaultHttp2.headerTableSize(),
                0,
                Integer.MAX_VALUE);
        int maxHeaderListSize = parseIntInRange(properties,
                "http2.maxHeaderListSize",
                defaultHttp2.maxHeaderListSize(),
                1,
                Integer.MAX_VALUE);
        boolean strictAlpn = parseBoolean(properties, "http2.strictAlpn", defaultHttp2.strictAlpn());

        Http2Config http2Config = new Http2Config(
                http2Enabled,
                h2cEnabled,
                maxConcurrentStreams,
                initialWindowSize,
                maxFrameSize,
                headerTableSize,
                maxHeaderListSize,
                strictAlpn);

        return new ServerConfig(port, threads, requestLimits, http2Config);
    }

    private static Properties loadProperties(Path configPath) throws IOException {
        String fileName = configPath.getFileName() == null ? "" : configPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return loadYamlProperties(configPath);
        }
        return loadJavaProperties(configPath);
    }

    private static Properties loadJavaProperties(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Properties loadYamlProperties(Path configPath) throws IOException {
        Properties properties = new Properties();
        Map<String, Object> flattened = new HashMap<>();

        try (InputStream inputStream = Files.newInputStream(configPath)) {
            Object loaded = new Yaml().load(inputStream);
            if (loaded == null) {
                return properties;
            }
            if (!(loaded instanceof Map<?, ?> rawRoot)) {
                throw new IllegalArgumentException("YAML configuration root must be a mapping/object");
            }

            flattenYamlMap((Map<Object, Object>) rawRoot, "", flattened);
        }

        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            properties.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return properties;
    }

    private static void flattenYamlMap(Map<Object, Object> map, String prefix, Map<String, Object> output) {
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String keyPart)) {
                throw new IllegalArgumentException("YAML configuration keys must be strings");
            }

            String key = prefix.isEmpty() ? keyPart : prefix + "." + keyPart;
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nestedMap) {
                flattenYamlMap(castYamlMap(nestedMap), key, output);
                continue;
            }

            if (value instanceof Iterable<?> || value != null && value.getClass().isArray()) {
                throw new IllegalArgumentException("YAML configuration key '" + key + "' must be a scalar value");
            }

            output.put(key, value);
        }
    }

    private static Map<Object, Object> castYamlMap(Map<?, ?> nestedMap) {
        Map<Object, Object> casted = new HashMap<>();
        for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
            casted.put(entry.getKey(), entry.getValue());
        }
        return casted;
    }

    private static void validateKeys(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + key);
            }
        }
    }

    private static int parseIntInRange(Properties properties, String key, int defaultValue, int minInclusive,
            int maxInclusive) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        final int parsedValue;
        try {
            parsedValue = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Configuration key '" + key + "' must be an integer", exception);
        }

        if (parsedValue < minInclusive || parsedValue > maxInclusive) {
            throw new IllegalArgumentException(
                    "Configuration key '" + key + "' out of range [" + minInclusive + ", " + maxInclusive + "]");
        }

        return parsedValue;
    }

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("Configuration key '" + key + "' must be a boolean (true/false)");
    }
}
