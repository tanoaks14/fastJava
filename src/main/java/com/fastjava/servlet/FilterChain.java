package com.fastjava.servlet;

/**
 * Executes the next filter or terminal servlet.
 */
public interface FilterChain {
    void doFilter(HttpServletRequest request, HttpServletResponse response) throws ServletException;
}