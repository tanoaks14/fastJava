package com.fastjava.server;

import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;
import org.junit.Test;

import static org.junit.Assert.*;

public class ServletRouterSimdTest {

    @Test
    public void prefixAndSuffixSimdMatchScalar() {
        String[] paths = {
                "/api/users/42",
                "/assets/app.js",
                "/index.html",
                "/api/",
                "/download/file.tar.gz"
        };
        String[] prefixes = { "/api", "/assets", "/download", "/missing" };
        String[] suffixes = { ".js", ".html", ".gz", ".json" };

        for (String path : paths) {
            for (String prefix : prefixes) {
                assertEquals(
                        ServletRouter.prefixMatchScalar(path, prefix),
                        ServletRouter.prefixMatchSimd(path, prefix));
            }
            for (String suffix : suffixes) {
                assertEquals(
                        ServletRouter.suffixMatchScalar(path, suffix),
                        ServletRouter.suffixMatchSimd(path, suffix));
            }
        }
    }

    @Test
    public void unicodePathsFallBackToScalarSemantics() {
        String unicodePath = "/caf\u00e9/report.json";
        assertEquals(
                ServletRouter.prefixMatchScalar(unicodePath, "/caf\u00e9"),
                ServletRouter.prefixMatchSimd(unicodePath, "/caf\u00e9"));
        assertEquals(
                ServletRouter.suffixMatchScalar(unicodePath, ".json"),
                ServletRouter.suffixMatchSimd(unicodePath, ".json"));
    }

    @Test
    public void resolveMatchesWildcardPatterns() throws ServletException {
        ServletRouter router = new ServletRouter();

        HttpServlet apiServlet = new HttpServlet() {
            @Override
            public void service(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(200);
            }
        };
        HttpServlet extServlet = new HttpServlet() {
            @Override
            public void service(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(200);
            }
        };

        router.addServletPattern("/api/*", apiServlet);
        router.addServletPattern("*.json", extServlet);

        ServletRouter.DispatchTarget api = router.resolve("/api/orders");
        ServletRouter.DispatchTarget json = router.resolve("/reports/latest.json");

        assertNotNull(api);
        assertSame(apiServlet, api.servlet());
        assertNotNull(json);
        assertSame(extServlet, json.servlet());
    }
}
