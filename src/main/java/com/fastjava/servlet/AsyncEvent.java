package com.fastjava.servlet;

/**
 * Event context delivered to async lifecycle listeners.
 */
public final class AsyncEvent {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final Throwable throwable;

    public AsyncEvent(HttpServletRequest request, HttpServletResponse response, Throwable throwable) {
        this.request = request;
        this.response = response;
        this.throwable = throwable;
    }

    public HttpServletRequest getSuppliedRequest() {
        return request;
    }

    public HttpServletResponse getSuppliedResponse() {
        return response;
    }

    public Throwable getThrowable() {
        return throwable;
    }
}
