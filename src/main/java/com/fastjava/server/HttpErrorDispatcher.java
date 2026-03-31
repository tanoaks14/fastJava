package com.fastjava.server;

import com.fastjava.http.impl.DefaultHttpServletRequest;
import com.fastjava.http.impl.DefaultHttpServletResponse;

/**
 * Dispatches a failed request to a registered error-page servlet, setting the
 * standard {@code javax.servlet.error.*} request attributes before invoking the
 * servlet.
 *
 * <p>
 * Attribute names follow the Servlet 3.1 specification §10.9.1.
 */
final class HttpErrorDispatcher {

    static final String ERROR_STATUS_CODE = "javax.servlet.error.status_code";
    static final String ERROR_EXCEPTION = "javax.servlet.error.exception";
    static final String ERROR_EXCEPTION_TYPE = "javax.servlet.error.exception_type";
    static final String ERROR_MESSAGE = "javax.servlet.error.message";
    static final String ERROR_REQUEST_URI = "javax.servlet.error.request_uri";

    private HttpErrorDispatcher() {
    }

    /**
     * Sets error attributes on {@code request} and dispatches to the error-page
     * servlet found at {@code errorPage.location()} inside {@code router}.
     *
     * <p>
     * If no servlet is registered at the error-page location, falls back to
     * {@link DefaultHttpServletResponse#sendError(int)}.
     *
     * @param request    original request (attributes will be mutated)
     * @param response   response to write to
     * @param exception  the causing exception, or {@code null} for status-only
     *                   errors
     * @param statusCode HTTP status code to set on the response
     * @param errorPage  the error-page descriptor (location must be registered in
     *                   router)
     * @param router     router used to look up the error-page servlet
     * @throws Exception if the error-page servlet itself throws
     */
    static void dispatch(DefaultHttpServletRequest request,
            DefaultHttpServletResponse response,
            Throwable exception,
            int statusCode,
            ErrorPage errorPage,
            ServletRouter router) throws Exception {
        // 1. Populate standard error attributes.
        request.setAttribute(ERROR_STATUS_CODE, statusCode);
        String uri = request.getRequestURI();
        String qs = request.getQueryString();
        String fullUri = (qs != null && !qs.isEmpty()) ? uri + "?" + qs : uri;
        request.setAttribute(ERROR_REQUEST_URI, escapeHtml(fullUri));
        if (exception != null) {
            request.setAttribute(ERROR_EXCEPTION, exception);
            request.setAttribute(ERROR_EXCEPTION_TYPE, exception.getClass());
            request.setAttribute(ERROR_MESSAGE, exception.getMessage() != null ? exception.getMessage() : "");
        }

        // 2. Resolve and invoke the error-page servlet.
        ServletRouter.DispatchTarget target = router.resolve(errorPage.location());
        if (target != null) {
            response.setStatus(statusCode);
            target.servlet().service(request, response);
        } else {
            // Error page misconfigured — fall back to plain sendError.
            response.sendError(statusCode);
        }
    }

    /**
     * HTML-encodes the five characters that must be escaped to prevent
     * reflected XSS when the request URI is embedded in an HTML error page.
     */
    private static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
