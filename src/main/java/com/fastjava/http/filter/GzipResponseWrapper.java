package com.fastjava.http.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.servlet.Cookie;
import com.fastjava.servlet.HttpServletResponse;

/**
 * Wraps an {@link HttpServletResponse} to capture body bytes written during
 * filter chain execution. The GzipFilter calls {@link #finish()} after the
 * chain returns to compress the captured bytes and inject them into the
 * underlying response via
 * {@link DefaultHttpServletResponse#setRawBody(byte[])}.
 *
 * <p>
 * All header and status mutations are delegated directly to the underlying
 * response so that response metadata is visible immediately (e.g. Content-Type
 * for MIME exclusion decisions). Only the body is captured in a private buffer.
 */
public class GzipResponseWrapper implements HttpServletResponse {

    private final DefaultHttpServletResponse delegate;
    private final ByteArrayOutputStream captureBuffer;
    private final PrintWriter captureWriter;
    /**
     * True when the servlet signalled an error via sendError() — bypass gzip in
     * that case.
     */
    private boolean errored = false;

    public GzipResponseWrapper(DefaultHttpServletResponse delegate) {
        this.delegate = delegate;
        this.captureBuffer = new ByteArrayOutputStream(4096);
        this.captureWriter = new PrintWriter(
                new OutputStreamWriter(captureBuffer, StandardCharsets.UTF_8.name()), false);
    }

    // ---- Body capture ----
    @Override
    public PrintWriter getWriter() {
        return captureWriter;
    }

    /**
     * Returns the bytes accumulated in the capture buffer so far. Flushes the
     * capture writer beforehand.
     */
    public byte[] getCapturedBytes() {
        captureWriter.flush();
        return captureBuffer.toByteArray();
    }

    // ---- Status / headers — delegate directly ----
    @Override
    public void setStatus(int sc) {
        delegate.setStatus(sc);
    }

    @Override
    public void setStatus(int sc, String sm) {
        delegate.setStatus(sc, sm);
    }

    @Override
    public int getStatus() {
        return delegate.getStatus();
    }

    @Override
    public void setHeader(String name, String value) {
        delegate.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        delegate.addHeader(name, value);
    }

    @Override
    public void addCookie(Cookie cookie) {
        delegate.addCookie(cookie);
    }

    @Override
    public void setIntHeader(String name, int value) {
        delegate.setIntHeader(name, value);
    }

    @Override
    public void setDateHeader(String name, long date) {
        delegate.setDateHeader(name, date);
    }

    @Override
    public void setContentType(String type) {
        delegate.setContentType(type);
    }

    @Override
    public void setContentLength(int len) {
        delegate.setContentLength(len);
    }

    @Override
    public void setCharacterEncoding(String c) {
        delegate.setCharacterEncoding(c);
    }

    @Override
    public String getContentType() {
        return delegate.getContentType();
    }

    @Override
    public boolean isChunkedResponseEnabled() {
        return delegate.isChunkedResponseEnabled();
    }

    @Override
    public boolean isCommitted() {
        return delegate.isCommitted();
    }

    @Override
    public void setChunkedResponseEnabled(boolean enabled) {
        delegate.setChunkedResponseEnabled(enabled);
    }

    /**
     * Only flush the capture writer; do not commit the underlying response yet.
     */
    @Override
    public void flushBuffer() {
        captureWriter.flush();
    }

    /**
     * Error path — skip compression and delegate body/status directly.
     */
    @Override
    public void sendError(int sc) {
        errored = true;
        captureWriter.flush();
        delegate.sendError(sc);
    }

    @Override
    public void sendRedirect(String location) {
        errored = true;
        captureWriter.flush();
        delegate.sendRedirect(location);
    }

    // ---- Output segments (executor always calls these on the delegate, not the
    // wrapper) ----
    @Override
    public byte[][] getOutputSegments() {
        return delegate.getOutputSegments();
    }

    @Override
    public byte[] getOutputBuffer() {
        return delegate.getOutputBuffer();
    }

    // ---- API used by GzipFilter ----
    /**
     * Returns {@code true} when the servlet invoked sendError()/sendRedirect(),
     * meaning the body was already written directly to the delegate.
     */
    public boolean isErrored() {
        return errored;
    }

    private static final class OutputStreamWriter extends Writer {

        private final OutputStream out;
        private final String charset;

        OutputStreamWriter(OutputStream out, String charset) {
            this.out = out;
            this.charset = charset;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            out.write(new String(cbuf, off, len).getBytes(charset));
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
