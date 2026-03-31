package com.fastjava.server;

import com.fastjava.http.response.HttpResponseBuilder;
import com.fastjava.sse.SseEmitter;
import java.nio.ByteBuffer;

public record HttpExecutionResult(ByteBuffer[] responseBuffers, FileResponseBody fileBody, boolean keepAlive,
		int statusCode, long bytesSent, boolean sseStream, SseEmitter sseEmitter) {

	public HttpExecutionResult(ByteBuffer[] responseBuffers, FileResponseBody fileBody, boolean keepAlive) {
		this(responseBuffers, fileBody, keepAlive, 200, estimateBytesSent(responseBuffers, fileBody), false, null);
	}

	public HttpExecutionResult(ByteBuffer[] responseBuffers, boolean keepAlive) {
		this(responseBuffers, null, keepAlive, 200, estimateBytesSent(responseBuffers, null), false, null);
	}

	public HttpExecutionResult(byte[][] responseSegments, FileResponseBody fileBody, boolean keepAlive) {
		this(wrapSegments(responseSegments), fileBody, keepAlive, 200,
				estimateBytesSent(wrapSegments(responseSegments), fileBody),
				false, null);
	}

	public HttpExecutionResult(byte[][] responseSegments, boolean keepAlive) {
		this(wrapSegments(responseSegments), null, keepAlive, 200,
				estimateBytesSent(wrapSegments(responseSegments), null),
				false, null);
	}

	public HttpExecutionResult(byte[][] responseSegments, FileResponseBody fileBody, boolean keepAlive,
			int statusCode, long bytesSent, boolean sseStream, SseEmitter sseEmitter) {
		this(wrapSegments(responseSegments), fileBody, keepAlive, statusCode, bytesSent, sseStream, sseEmitter);
	}

	public byte[] responseBytes() {
		if (responseBuffers == null || responseBuffers.length == 0) {
			return new byte[0];
		}
		int total = 0;
		for (ByteBuffer buffer : responseBuffers) {
			if (buffer != null) {
				total += buffer.remaining();
			}
		}
		byte[] merged = new byte[total];
		int position = 0;
		for (ByteBuffer buffer : responseBuffers) {
			if (buffer == null || !buffer.hasRemaining()) {
				continue;
			}
			ByteBuffer duplicate = buffer.duplicate();
			int length = duplicate.remaining();
			duplicate.get(merged, position, length);
			position += length;
		}
		return merged;
	}

	public byte[][] responseSegments() {
		if (responseBuffers == null || responseBuffers.length == 0) {
			return new byte[0][];
		}
		byte[][] segments = new byte[responseBuffers.length][];
		for (int i = 0; i < responseBuffers.length; i++) {
			ByteBuffer buffer = responseBuffers[i];
			if (buffer == null) {
				segments[i] = null;
				continue;
			}
			ByteBuffer duplicate = buffer.duplicate();
			byte[] bytes = new byte[duplicate.remaining()];
			duplicate.get(bytes);
			segments[i] = bytes;
		}
		return segments;
	}

	private static long estimateBytesSent(ByteBuffer[] responseBuffers, FileResponseBody fileBody) {
		long total = 0;
		if (responseBuffers != null) {
			for (ByteBuffer buffer : responseBuffers) {
				if (buffer != null) {
					total += buffer.remaining();
				}
			}
		}
		if (fileBody != null) {
			total += fileBody.length();
		}
		return total;
	}

	private static ByteBuffer[] wrapSegments(byte[][] responseSegments) {
		if (responseSegments == null || responseSegments.length == 0) {
			return new ByteBuffer[0];
		}
		ByteBuffer[] buffers = new ByteBuffer[responseSegments.length];
		for (int i = 0; i < responseSegments.length; i++) {
			byte[] segment = responseSegments[i];
			buffers[i] = segment == null ? null : ByteBuffer.wrap(segment);
		}
		return buffers;
	}
}