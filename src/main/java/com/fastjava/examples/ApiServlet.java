package com.fastjava.examples;

import com.fastjava.servlet.*;
import java.io.PrintWriter;

/**
 * Example: JSON API servlet.
 */
public class ApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setContentType("application/json");
        response.setStatus(200);

        PrintWriter out = response.getWriter();
        out.println("{");
        out.println("  \"status\": \"success\",");
        out.println("  \"message\": \"Hello from FastJava API\",");
        out.println("  \"method\": \"" + request.getMethod() + "\",");
        out.println("  \"path\": \"" + request.getRequestURI() + "\"");
        out.println("}");
        out.flush();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setContentType("application/json");
        response.setStatus(201);

        PrintWriter out = response.getWriter();
        out.println("{");
        out.println("  \"status\": \"created\",");
        out.println("  \"contentLength\": " + request.getContentLength());
        out.println("}");
        out.flush();
    }
}
