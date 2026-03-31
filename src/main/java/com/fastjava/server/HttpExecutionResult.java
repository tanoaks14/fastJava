package com.fastjava.server;

import com.fastjava.http.response.HttpResponseBuilder;
import com.fastjava.sse.SseEmitter;

public record HttpExecutionResult(byte[][] responseSegments, FileResponseBody fileBody, boolean keepAlive,
		int statusCode, long bytesSent, boolean sseStream, SseEmitter sseEmitter) {

	public HttpExecutionResult(byte[][] responseSegments, FileResponseBody fileBody, boolean keepAlive) {
		this(responseSegments, fileBody, keepAlive, 200, estimateBytesSent(responseSegments, fileBody), false, null);
	}

	public HttpExecutionResult(byte[][] responseSegments, boolean keepAlive) {
		this(responseSegments, null, keepAlive, 200, estimateBytesSent(responseSegments, null), false, null);
	}

	public byte[] responseBytes() {
		return HttpResponseBuilder.flattenSegments(responseSegments);
	}

	private static long estimateBytesSent(byte[][] responseSegments, FileResponseBody fileBody) {
		long total = 0;
		if (responseSegments != null) {
			for (byte[] segment : responseSegments) {
				if (segment != null) {
					total += segment.length;
				}
			}
		}
		if (fileBody != null) {
			total += fileBody.length();
		}
		return total;
	}
}