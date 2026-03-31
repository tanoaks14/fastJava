package com.fastjava.servlet;

/**
 * Minimal request dispatcher contract for internal servlet dispatch.
 */
public interface RequestDispatcher {

    void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException;

    void include(HttpServletRequest request, HttpServletResponse response) throws ServletException;
}