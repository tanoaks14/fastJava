package com.fastjava.servlet;

/**
 * Abstract HttpServlet - implementors override doGet, doPost, etc.
 */
public abstract class HttpServlet {

    private ServletConfig servletConfig;

    public void init(ServletConfig config) throws ServletException {
        this.servletConfig = config;
        init();
    }

    public void init() throws ServletException {
    }

    public void destroy() {
    }

    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    public String getServletName() {
        if (servletConfig != null) {
            return servletConfig.getServletName();
        }
        return getClass().getSimpleName();
    }

    public void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        String method = request.getMethod();

        switch (method) {
            case "GET" -> doGet(request, response);
            case "HEAD" -> doHead(request, response);
            case "POST" -> doPost(request, response);
            case "PUT" -> doPut(request, response);
            case "DELETE" -> doDelete(request, response);
            case "OPTIONS" -> doOptions(request, response);
            case "TRACE" -> doTrace(request, response);
            default -> throw new ServletException("HTTP method not supported: " + method);
        }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        methodNotAllowed(response);
    }

    protected void doHead(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        methodNotAllowed(response);
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        methodNotAllowed(response);
    }

    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        methodNotAllowed(response);
    }

    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        methodNotAllowed(response);
    }

    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setHeader("Allow", "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE");
    }

    protected void doTrace(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setStatus(405); // Method Not Allowed
    }

    private void methodNotAllowed(HttpServletResponse response) {
        response.setStatus(405);
    }
}
