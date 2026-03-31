package com.fastjava.server;

/**
 * Maps an HTTP status code or exception type to an error-page location.
 *
 * <p>
 * Equivalent to a {@code <error-page>} element in a Servlet 3.x deployment
 * descriptor. Exactly one of {@code statusCode} or {@code exceptionType} must
 * be set; the other should be {@code 0} / {@code null}.
 *
 * <pre>{@code
 * // Status-code mapping
 * router.addErrorPage(ErrorPage.forStatus(404, "/errors/not-found"));
 *
 * // Exception-type mapping
 * router.addErrorPage(ErrorPage.forException(IllegalArgumentException.class, "/errors/bad-request"));
 * }</pre>
 */
public record ErrorPage(int statusCode, Class<? extends Throwable> exceptionType, String location) {

    /**
     * Creates an error page mapped to an HTTP status code.
     *
     * @param statusCode HTTP status code (e.g. 404, 500)
     * @param location   servlet path that handles the error (must be registered)
     */
    public static ErrorPage forStatus(int statusCode, String location) {
        return new ErrorPage(statusCode, null, location);
    }

    /**
     * Creates an error page mapped to an exception type.
     *
     * @param exceptionType throwable class to match (exact match only)
     * @param location      servlet path that handles the error (must be registered)
     */
    public static ErrorPage forException(Class<? extends Throwable> exceptionType, String location) {
        return new ErrorPage(0, exceptionType, location);
    }
}
