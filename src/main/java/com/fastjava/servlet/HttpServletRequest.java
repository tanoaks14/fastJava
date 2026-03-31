package com.fastjava.servlet;

import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * API-compatible minimal Servlet spec for FastJava.
 * Similar structure to Jakarta Servlet (formerly Java Servlet API).
 */
public interface HttpServletRequest {

    // Request Methods
    String getMethod();

    String getRequestURI();

    String getQueryString();

    String getProtocol();

    // Headers
    String getHeader(String name);

    Enumeration<String> getHeaderNames();

    Enumeration<String> getHeaders(String name);

    int getIntHeader(String name);

    long getDateHeader(String name);

    // Cookies
    default List<Cookie> getCookies() {
        return List.of();
    }

    default Cookie getCookie(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Cookie cookie : getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    // Parameters
    String getParameter(String name);

    Map<String, String[]> getParameterMap();

    default Collection<Part> getParts() {
        return List.of();
    }

    default Part getPart(String name) {
        return null;
    }

    // Request Body
    InputStream getInputStream();

    String getCharacterEncoding();

    int getContentLength();

    String getContentType();

    // Socket Info
    String getRemoteAddr();

    String getRemoteHost();

    int getRemotePort();

    String getLocalAddr();

    String getLocalName();

    int getLocalPort();

    int getServerPort();

    // Attributes
    Object getAttribute(String name);

    void setAttribute(String name, Object object);

    default RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    // Session
    HttpSession getSession(boolean create);

    HttpSession getSession();

    default AsyncContext startAsync() {
        throw new IllegalStateException("Async processing is not supported for this request");
    }

    default boolean isAsyncStarted() {
        return false;
    }
}
