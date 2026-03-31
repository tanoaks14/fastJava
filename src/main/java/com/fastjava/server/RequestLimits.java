package com.fastjava.server;

public record RequestLimits(
                int maxRequestSize,
                int keepAliveTimeoutMillis,
                int readTimeoutMillis,
                int maxRequestLineBytes,
                int maxHeaderBytes,
                int maxBodyBytes,
                int maxChunkSizeBytes,
                int maxChunkCount,
                int writeTimeoutMillis,
                int maxMultipartBytes,
                int maxMultipartPartBytes,
                int multipartMemoryThresholdBytes,
                int maxConcurrentConnections,
                int keepAlivePressureQueueThreshold) {

        public RequestLimits {
                readTimeoutMillis = Math.max(1, readTimeoutMillis);
                maxMultipartBytes = Math.max(1, maxMultipartBytes);
                maxMultipartPartBytes = Math.max(1, maxMultipartPartBytes);
                multipartMemoryThresholdBytes = Math.max(1, multipartMemoryThresholdBytes);
                maxConcurrentConnections = Math.max(1, maxConcurrentConnections);
                keepAlivePressureQueueThreshold = Math.max(0, keepAlivePressureQueueThreshold);
        }

        public RequestLimits(
                        int maxRequestSize,
                        int keepAliveTimeoutMillis,
                        int maxRequestLineBytes,
                        int maxHeaderBytes,
                        int maxBodyBytes) {
                this(maxRequestSize, keepAliveTimeoutMillis, keepAliveTimeoutMillis, maxRequestLineBytes,
                                maxHeaderBytes, maxBodyBytes,
                                Math.max(1, maxBodyBytes), Math.max(1, maxRequestSize), 30_000,
                                Math.max(1, maxBodyBytes), Math.max(1, maxBodyBytes), 64 * 1024,
                                4_096, 800);
        }

        public RequestLimits(
                        int maxRequestSize,
                        int keepAliveTimeoutMillis,
                        int maxRequestLineBytes,
                        int maxHeaderBytes,
                        int maxBodyBytes,
                        int writeTimeoutMillis) {
                this(maxRequestSize, keepAliveTimeoutMillis, keepAliveTimeoutMillis, maxRequestLineBytes,
                                maxHeaderBytes, maxBodyBytes,
                                Math.max(1, maxBodyBytes), Math.max(1, maxRequestSize), writeTimeoutMillis,
                                Math.max(1, maxBodyBytes), Math.max(1, maxBodyBytes), 64 * 1024,
                                4_096, 800);
        }

        public RequestLimits(
                        int maxRequestSize,
                        int keepAliveTimeoutMillis,
                        int maxRequestLineBytes,
                        int maxHeaderBytes,
                        int maxBodyBytes,
                        int maxChunkSizeBytes,
                        int maxChunkCount) {
                this(maxRequestSize, keepAliveTimeoutMillis, keepAliveTimeoutMillis, maxRequestLineBytes,
                                maxHeaderBytes, maxBodyBytes,
                                maxChunkSizeBytes, maxChunkCount, 30_000,
                                Math.max(1, maxBodyBytes), Math.max(1, maxBodyBytes), 64 * 1024,
                                4_096, 800);
        }

        public RequestLimits(
                        int maxRequestSize,
                        int keepAliveTimeoutMillis,
                        int maxRequestLineBytes,
                        int maxHeaderBytes,
                        int maxBodyBytes,
                        int maxChunkSizeBytes,
                        int maxChunkCount,
                        int writeTimeoutMillis) {
                this(maxRequestSize, keepAliveTimeoutMillis, keepAliveTimeoutMillis, maxRequestLineBytes,
                                maxHeaderBytes, maxBodyBytes,
                                maxChunkSizeBytes, maxChunkCount, writeTimeoutMillis,
                                Math.max(1, maxBodyBytes), Math.max(1, maxBodyBytes), 64 * 1024,
                                4_096, 800);
        }

        public RequestLimits(
                        int maxRequestSize,
                        int keepAliveTimeoutMillis,
                        int readTimeoutMillis,
                        int maxRequestLineBytes,
                        int maxHeaderBytes,
                        int maxBodyBytes,
                        int maxChunkSizeBytes,
                        int maxChunkCount,
                        int writeTimeoutMillis,
                        int maxMultipartBytes,
                        int maxMultipartPartBytes,
                        int multipartMemoryThresholdBytes) {
                this(maxRequestSize, keepAliveTimeoutMillis, readTimeoutMillis, maxRequestLineBytes, maxHeaderBytes,
                                maxBodyBytes,
                                maxChunkSizeBytes, maxChunkCount, writeTimeoutMillis, maxMultipartBytes,
                                maxMultipartPartBytes,
                                multipartMemoryThresholdBytes, 4_096, 800);
        }

        public RequestLimits withMultipartLimits(int maxMultipartBytes, int maxMultipartPartBytes,
                        int multipartMemoryThresholdBytes) {
                return new RequestLimits(
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
        }

        public static RequestLimits defaults(int maxRequestSize) {
                return new RequestLimits(maxRequestSize, 5_000, 2_000, 4_096, 8_192, maxRequestSize,
                                maxRequestSize, maxRequestSize, 30_000,
                                maxRequestSize, maxRequestSize, 64 * 1024,
                                4_096, 800);
        }
}