package com.fastjava.servlet;

/**
 * Listener callbacks for async request lifecycle events.
 */
public interface AsyncListener {

    default void onComplete(AsyncEvent event) {
    }

    default void onTimeout(AsyncEvent event) {
    }

    default void onError(AsyncEvent event) {
    }

    default void onStartAsync(AsyncEvent event) {
    }
}
