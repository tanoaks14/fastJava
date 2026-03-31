package com.fastjava.servlet;

/**
 * Minimal async request context for servlet-style deferred responses.
 */
public interface AsyncContext {

    void complete();

    void dispatch();

    void dispatch(String path);

    void addListener(AsyncListener listener);

    void setTimeout(long timeoutMillis);

    long getTimeout();
}
