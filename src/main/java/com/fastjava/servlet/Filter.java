package com.fastjava.servlet;

/**
 * Minimal request/response filter contract.
 */
public interface Filter {
    default void init(FilterConfig filterConfig) throws ServletException {
    }

    void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException;

    default void destroy() {
    }
}