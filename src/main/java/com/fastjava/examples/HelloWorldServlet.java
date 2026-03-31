package com.fastjava.examples;

import com.fastjava.servlet.*;
import java.io.PrintWriter;

/**
 * Example: Simple "Hello World" servlet.
 */
public class HelloWorldServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html>");
        out.println("<head><title>Hello World</title></head>");
        out.println("<body>");
        out.println("<h1>Hello from FastJava SIMD Server!</h1>");
        out.println("<p>Method: " + request.getMethod() + "</p>");
        out.println("<p>Path: " + request.getRequestURI() + "</p>");
        out.println("</body>");
        out.println("</html>");
        out.flush();
    }
}
