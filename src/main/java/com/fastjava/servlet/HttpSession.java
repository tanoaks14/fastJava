package com.fastjava.servlet;

import java.util.Enumeration;

/**
 * HTTP Session interface for session management.
 */
public interface HttpSession {
    String getId();

    long getCreationTime();

    long getLastAccessedTime();

    Object getAttribute(String name);

    void setAttribute(String name, Object value);

    void removeAttribute(String name);

    Enumeration<String> getAttributeNames();

    void invalidate();

    boolean isNew();
}
