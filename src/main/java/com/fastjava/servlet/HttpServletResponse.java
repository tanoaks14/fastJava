package com.fastjava.servlet;

import java.io.PrintWriter;

/**
 * API-compatible minimal Servlet Response interface.
 */
public interface HttpServletResponse {

    // Status
    void setStatus(int sc);

    void setStatus(int sc, String sm);

    int getStatus();

    // Headers
    void setHeader(String name, String value);

    void addHeader(String name, String value);

    default void addCookie(Cookie cookie) {
        if (cookie == null) {
            return;
        }
        addHeader("Set-Cookie", cookie.getName() + "=" + cookie.getValue());
    }

    void setIntHeader(String name, int value);

    void setDateHeader(String name, long date);

    // Content
    void setContentType(String type);

    void setContentLength(int len);

    void setCharacterEncoding(String charset);

    String getContentType();

    // Output
    PrintWriter getWriter();

    byte[][] getOutputSegments();

    byte[] getOutputBuffer();

    void setChunkedResponseEnabled(boolean enabled);

    boolean isChunkedResponseEnabled();

    void flushBuffer();

    // Commit
    boolean isCommitted();

    void sendError(int sc);

    void sendRedirect(String location);
}
