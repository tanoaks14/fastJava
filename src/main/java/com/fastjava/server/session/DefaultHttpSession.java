package com.fastjava.server.session;

import com.fastjava.servlet.HttpSession;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory HttpSession implementation.
 */
public final class DefaultHttpSession implements HttpSession {

    private final String id;
    private final long creationTime;
    private final AtomicLong lastAccessedTime;
    private final ConcurrentHashMap<String, Object> attributes;
    private final AtomicBoolean valid;
    private final AtomicBoolean isNew;
    private final int maxAttributes;
    private final Runnable invalidateHook;

    DefaultHttpSession(String id, long creationTimeMillis, int maxAttributes, Runnable invalidateHook) {
        this.id = id;
        this.creationTime = creationTimeMillis;
        this.lastAccessedTime = new AtomicLong(creationTimeMillis);
        this.attributes = new ConcurrentHashMap<>();
        this.valid = new AtomicBoolean(true);
        this.isNew = new AtomicBoolean(true);
        this.maxAttributes = maxAttributes;
        this.invalidateHook = invalidateHook;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public long getCreationTime() {
        assertValid();
        return creationTime;
    }

    @Override
    public long getLastAccessedTime() {
        assertValid();
        return lastAccessedTime.get();
    }

    @Override
    public Object getAttribute(String name) {
        assertValid();
        if (name == null || name.isBlank()) {
            return null;
        }
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        assertValid();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Session attribute name cannot be blank");
        }
        if (value == null) {
            attributes.remove(name);
            return;
        }
        if (!attributes.containsKey(name) && attributes.size() >= maxAttributes) {
            throw new IllegalStateException("Session attribute capacity exceeded");
        }
        attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        assertValid();
        if (name == null || name.isBlank()) {
            return;
        }
        attributes.remove(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        assertValid();
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void invalidate() {
        if (valid.compareAndSet(true, false)) {
            attributes.clear();
            if (invalidateHook != null) {
                invalidateHook.run();
            }
        }
    }

    @Override
    public boolean isNew() {
        assertValid();
        return isNew.get();
    }

    void touch(long nowMillis) {
        if (valid.get()) {
            lastAccessedTime.set(nowMillis);
        }
    }

    void markEstablished() {
        isNew.set(false);
    }

    boolean isValidSession() {
        return valid.get();
    }

    Map<String, Object> snapshotAttributes() {
        return Map.copyOf(attributes);
    }

    private void assertValid() {
        if (!valid.get()) {
            throw new IllegalStateException("Session has been invalidated");
        }
    }
}
