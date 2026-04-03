package com.fastjava.bench.profile;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;

import com.fastjava.server.FastJavaNioServer;
import com.fastjava.server.RequestLimits;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;

public final class FastJavaIsolatedProfileServer {

    private FastJavaIsolatedProfileServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        boolean useStaticRoute = args.length > 1 && "static".equalsIgnoreCase(args[1]);
        FastJavaNioServer server = new FastJavaNioServer(port, RequestLimits.defaults(64 * 1024));
        if (useStaticRoute) {
            server.addStaticPlainTextRoute("/hello", "ok");
        } else {
            server.addServlet("/hello", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getWriter().write("ok");
                }
            });
        }
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "fastjava-profile-shutdown"));
        System.out.println("FASTJAVA_PROFILE_PID=" + ManagementFactory.getRuntimeMXBean().getPid());
        System.out.println("FASTJAVA_PROFILE_PORT=" + server.getBoundPort());
        System.out.println("FASTJAVA_PROFILE_MODE=" + (useStaticRoute ? "static" : "servlet"));
        new CountDownLatch(1).await();
    }
}
