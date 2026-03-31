package com.fastjava.server.config;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerConfigLoaderTest {

    @Test
    public void loadsDefaultsWhenFileIsEmpty() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".properties");
        try {
            ServerConfig config = ServerConfigLoader.load(configPath);
            assertEquals(ServerConfig.DEFAULT_PORT, config.port());
            assertEquals(ServerConfig.DEFAULT_THREADS, config.threads());
            assertEquals(ServerConfig.DEFAULT_MAX_REQUEST_SIZE, config.requestLimits().maxRequestSize());
            assertEquals(false, config.http2Config().enabled());
            assertEquals(false, config.http2Config().h2cEnabled());
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void loadsConfiguredValues() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".properties");
        try {
            Files.writeString(configPath,
                    "server.port=9090\n"
                            + "server.threads=24\n"
                            + "request.maxRequestSize=32768\n"
                            + "request.keepAliveTimeoutMillis=1500\n"
                            + "request.readTimeoutMillis=900\n"
                            + "request.maxRequestLineBytes=2048\n"
                            + "request.maxHeaderBytes=4096\n"
                            + "request.maxBodyBytes=65536\n"
                            + "request.maxChunkSizeBytes=8192\n"
                            + "request.maxChunkCount=64\n"
                            + "request.writeTimeoutMillis=7000\n"
                            + "request.maxMultipartBytes=131072\n"
                            + "request.maxMultipartPartBytes=65536\n"
                            + "request.multipartMemoryThresholdBytes=16384\n"
                            + "request.maxConcurrentConnections=128\n"
                            + "request.keepAlivePressureQueueThreshold=64\n"
                            + "http2.enabled=true\n"
                            + "http2.h2cEnabled=true\n"
                            + "http2.maxConcurrentStreams=250\n"
                            + "http2.initialWindowSize=131072\n"
                            + "http2.maxFrameSize=32768\n"
                            + "http2.headerTableSize=8192\n"
                            + "http2.maxHeaderListSize=65536\n"
                            + "http2.strictAlpn=true\n");

            ServerConfig config = ServerConfigLoader.load(configPath);
            assertEquals(9090, config.port());
            assertEquals(24, config.threads());
            assertEquals(32768, config.requestLimits().maxRequestSize());
            assertEquals(1500, config.requestLimits().keepAliveTimeoutMillis());
            assertEquals(900, config.requestLimits().readTimeoutMillis());
            assertEquals(2048, config.requestLimits().maxRequestLineBytes());
            assertEquals(4096, config.requestLimits().maxHeaderBytes());
            assertEquals(65536, config.requestLimits().maxBodyBytes());
            assertEquals(8192, config.requestLimits().maxChunkSizeBytes());
            assertEquals(64, config.requestLimits().maxChunkCount());
            assertEquals(7000, config.requestLimits().writeTimeoutMillis());
            assertEquals(131072, config.requestLimits().maxMultipartBytes());
            assertEquals(65536, config.requestLimits().maxMultipartPartBytes());
            assertEquals(16384, config.requestLimits().multipartMemoryThresholdBytes());
            assertEquals(128, config.requestLimits().maxConcurrentConnections());
            assertEquals(64, config.requestLimits().keepAlivePressureQueueThreshold());
            assertEquals(true, config.http2Config().enabled());
            assertEquals(true, config.http2Config().h2cEnabled());
            assertEquals(250, config.http2Config().maxConcurrentStreams());
            assertEquals(131072, config.http2Config().initialWindowSize());
            assertEquals(32768, config.http2Config().maxFrameSize());
            assertEquals(8192, config.http2Config().headerTableSize());
            assertEquals(65536, config.http2Config().maxHeaderListSize());
            assertEquals(true, config.http2Config().strictAlpn());
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void rejectsUnknownKeys() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".properties");
        try {
            Files.writeString(configPath, "server.unknown=1\n");
            try {
                ServerConfigLoader.load(configPath);
            } catch (IllegalArgumentException exception) {
                assertTrue(exception.getMessage().contains("Unknown configuration key"));
                return;
            }
            throw new AssertionError("Expected IllegalArgumentException");
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void rejectsOutOfRangeValues() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".properties");
        try {
            Files.writeString(configPath, "server.port=70000\n");
            try {
                ServerConfigLoader.load(configPath);
            } catch (IllegalArgumentException exception) {
                assertTrue(exception.getMessage().contains("out of range"));
                return;
            }
            throw new AssertionError("Expected IllegalArgumentException");
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void loadsConfiguredValuesFromYaml() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".yaml");
        try {
            Files.writeString(configPath,
                    "server:\n"
                            + "  port: 8181\n"
                            + "  threads: 12\n"
                            + "request:\n"
                            + "  maxRequestSize: 32768\n"
                            + "  keepAliveTimeoutMillis: 1000\n"
                            + "  readTimeoutMillis: 500\n"
                            + "  maxRequestLineBytes: 4096\n"
                            + "  maxHeaderBytes: 8192\n"
                            + "  maxBodyBytes: 16384\n"
                            + "  maxChunkSizeBytes: 4096\n"
                            + "  maxChunkCount: 32\n"
                            + "  writeTimeoutMillis: 6000\n"
                            + "  maxMultipartBytes: 32768\n"
                            + "  maxMultipartPartBytes: 16384\n"
                            + "  multipartMemoryThresholdBytes: 8192\n"
                            + "  maxConcurrentConnections: 256\n"
                            + "  keepAlivePressureQueueThreshold: 128\n"
                            + "http2:\n"
                            + "  enabled: true\n"
                            + "  h2cEnabled: false\n"
                            + "  maxConcurrentStreams: 200\n"
                            + "  initialWindowSize: 98304\n"
                            + "  maxFrameSize: 65535\n"
                            + "  headerTableSize: 4096\n"
                            + "  maxHeaderListSize: 32768\n"
                            + "  strictAlpn: false\n");

            ServerConfig config = ServerConfigLoader.load(configPath);
            assertEquals(8181, config.port());
            assertEquals(12, config.threads());
            assertEquals(32768, config.requestLimits().maxRequestSize());
            assertEquals(1000, config.requestLimits().keepAliveTimeoutMillis());
            assertEquals(500, config.requestLimits().readTimeoutMillis());
            assertEquals(4096, config.requestLimits().maxRequestLineBytes());
            assertEquals(8192, config.requestLimits().maxHeaderBytes());
            assertEquals(16384, config.requestLimits().maxBodyBytes());
            assertEquals(4096, config.requestLimits().maxChunkSizeBytes());
            assertEquals(32, config.requestLimits().maxChunkCount());
            assertEquals(6000, config.requestLimits().writeTimeoutMillis());
            assertEquals(32768, config.requestLimits().maxMultipartBytes());
            assertEquals(16384, config.requestLimits().maxMultipartPartBytes());
            assertEquals(8192, config.requestLimits().multipartMemoryThresholdBytes());
            assertEquals(256, config.requestLimits().maxConcurrentConnections());
            assertEquals(128, config.requestLimits().keepAlivePressureQueueThreshold());
            assertEquals(true, config.http2Config().enabled());
            assertEquals(false, config.http2Config().h2cEnabled());
            assertEquals(200, config.http2Config().maxConcurrentStreams());
            assertEquals(98304, config.http2Config().initialWindowSize());
            assertEquals(65535, config.http2Config().maxFrameSize());
            assertEquals(4096, config.http2Config().headerTableSize());
            assertEquals(32768, config.http2Config().maxHeaderListSize());
            assertEquals(false, config.http2Config().strictAlpn());
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void rejectsUnknownYamlKeys() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".yml");
        try {
            Files.writeString(configPath,
                    "server:\n"
                            + "  port: 8080\n"
                            + "  unknown: 1\n");

            try {
                ServerConfigLoader.load(configPath);
            } catch (IllegalArgumentException exception) {
                assertTrue(exception.getMessage().contains("Unknown configuration key"));
                return;
            }
            throw new AssertionError("Expected IllegalArgumentException");
        } finally {
            Files.deleteIfExists(configPath);
        }
    }
}
