package com.fastjava.http.impl;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.server.RequestLimits;
import com.fastjava.server.session.InMemorySessionManager;
import com.fastjava.server.session.SessionConfig;
import com.fastjava.servlet.AsyncContext;
import com.fastjava.servlet.Part;
import com.fastjava.servlet.HttpSession;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.Assert.*;

public class DefaultHttpServletRequestTest {

        @Test
        public void testGetParameterDecodesUrlEncodedQuery() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET /search?name=John+Doe&email=test%40example.com&path=%2Fapi%2Fv1 HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                assertEquals("John Doe", request.getParameter("name"));
                assertEquals("test@example.com", request.getParameter("email"));
                assertEquals("/api/v1", request.getParameter("path"));
        }

        @Test
        public void testDuplicateQueryKeysKeepFirstParameterAndAllValuesInMap() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET /items?id=1&id=2&id=3 HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                assertEquals("1", request.getParameter("id"));
                assertArrayEquals(new String[] { "1", "2", "3" }, request.getParameterMap().get("id"));
        }

        @Test
        public void testFormBodyIsMergedWithQueryAndQueryWinsOnConflict() {
                String body = "id=fromBody&note=hello+world";
                DefaultHttpServletRequest request = parseRequest(
                                "POST /submit?id=fromQuery HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                Map<String, String[]> params = request.getParameterMap();
                assertEquals("fromQuery", request.getParameter("id"));
                assertArrayEquals(new String[] { "fromQuery" }, params.get("id"));
                assertEquals("hello world", request.getParameter("note"));
        }

        @Test
        public void testMalformedPercentEncodingFallsBackToRawValue() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET /bad?value=%E0%A4%A HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                assertEquals("%E0%A4%A", request.getParameter("value"));
        }

        @Test
        public void testParsesCookieHeaderIntoRequestCookies() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: session=abc123; theme=dark\r\n"
                                                + "\r\n");

                assertEquals(2, request.getCookies().size());
                assertEquals("abc123", request.getCookie("session").getValue());
                assertEquals("dark", request.getCookie("theme").getValue());
        }

        @Test
        public void testCookieParserIgnoresMalformedTokensAndKeepsLastDuplicate() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: good=one; malformed; good=two; =missing\r\n"
                                                + "\r\n");

                assertEquals(1, request.getCookies().size());
                assertEquals("two", request.getCookie("good").getValue());
        }

        @Test
        public void testCookieParserUnquotesCookieValues() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: token=\"quoted\"\r\n"
                                                + "\r\n");

                assertEquals("quoted", request.getCookie("token").getValue());
        }

        @Test
        public void testIgnoresBodyWhenContentTypeIsNotFormUrlEncoded() {
                String body = "id=fromBody";
                DefaultHttpServletRequest request = parseRequest(
                                "POST /submit?id=fromQuery HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: application/json\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                assertEquals("fromQuery", request.getParameter("id"));
        }

        @Test
        public void testMultipartFormDataTextFieldsAreExposedAsParameters() {
                String boundary = "----fastjava-boundary";
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"title\"\r\n"
                                + "\r\n"
                                + "hello world\r\n"
                                + "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"count\"\r\n"
                                + "\r\n"
                                + "42\r\n"
                                + "--" + boundary + "--\r\n";

                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                assertEquals("hello world", request.getParameter("title"));
                assertEquals("42", request.getParameter("count"));
        }

        @Test
        public void testMultipartQueryParametersTakePrecedenceOverBody() {
                String boundary = "----fastjava-precedence";
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"id\"\r\n"
                                + "\r\n"
                                + "fromBody\r\n"
                                + "--" + boundary + "--\r\n";

                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload?id=fromQuery HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                assertEquals("fromQuery", request.getParameter("id"));
        }

        @Test
        public void testMultipartFilePartAvailableViaGetPartAndGetParts() {
                String boundary = "----fastjava-file";
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"title\"\r\n"
                                + "\r\n"
                                + "hello world\r\n"
                                + "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"hello.txt\"\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "\r\n"
                                + "abc123\r\n"
                                + "--" + boundary + "--\r\n";

                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                assertEquals("hello world", request.getParameter("title"));
                Part upload = request.getPart("upload");
                assertNotNull(upload);
                assertEquals("upload", upload.getName());
                assertEquals("hello.txt", upload.getSubmittedFileName());
                assertEquals("text/plain", upload.getContentType());
                assertArrayEquals("abc123".getBytes(StandardCharsets.UTF_8), upload.getBytes());

                Collection<Part> parts = request.getParts();
                assertEquals(2, parts.size());
        }

        @Test
        public void testGetPartReturnsNullWhenMissing() {
                String boundary = "----fastjava-missing";
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"title\"\r\n"
                                + "\r\n"
                                + "hello world\r\n"
                                + "--" + boundary + "--\r\n";

                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body);

                assertNull(request.getPart("upload"));
        }

        @Test
        public void testMultipartFilePartSpillsToDiskAndSupportsWriteToHook() throws Exception {
                String boundary = "----fastjava-spill-request";
                String payload = "x".repeat(4096);
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"big.bin\"\r\n"
                                + "Content-Type: application/octet-stream\r\n"
                                + "\r\n"
                                + payload + "\r\n"
                                + "--" + boundary + "--\r\n";

                RequestLimits limits = RequestLimits.defaults(32 * 1024)
                                .withMultipartLimits(32 * 1024, 8 * 1024, 128);
                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body,
                                limits);

                Part upload = request.getPart("upload");
                assertNotNull(upload);
                assertEquals(payload.length(), upload.getSize());

                Path out = Files.createTempFile("fastjava-upload-copy-", ".bin");
                upload.writeTo(out);
                assertEquals(payload, Files.readString(out, StandardCharsets.UTF_8));
        }

        @Test
        public void testMultipartPartLimitFromRequestLimitsRejectsOversizedPart() {
                String boundary = "----fastjava-limit-request";
                String payload = "x".repeat(512);
                String body = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"big.bin\"\r\n"
                                + "Content-Type: application/octet-stream\r\n"
                                + "\r\n"
                                + payload + "\r\n"
                                + "--" + boundary + "--\r\n";

                RequestLimits limits = RequestLimits.defaults(32 * 1024)
                                .withMultipartLimits(32 * 1024, 128, 64);
                DefaultHttpServletRequest request = parseRequest(
                                "POST /upload HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                                                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                                                + "\r\n"
                                                + "\r\n"
                                                + body,
                                limits);

                assertTrue(request.getParts().isEmpty());
                assertNull(request.getPart("upload"));
        }

        @Test
        public void testAttributesRemainNullUntilSet() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                assertNull(request.getAttribute("missing"));
                request.setAttribute("traceId", "abc123");
                assertEquals("abc123", request.getAttribute("traceId"));
        }

        @Test
        public void testGetSessionCreatesSessionAndWritesSetCookie() {
                InMemorySessionManager sessionManager = new InMemorySessionManager(SessionConfig.defaults());
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                DefaultHttpServletResponse response = new DefaultHttpServletResponse(256);

                HttpSession session = request.getSession(true);
                assertNotNull(session);
                assertTrue(session.isNew());

                request.applySessionToResponse(response, false);
                response.getWriter().print("ok");
                String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
                assertTrue(payload.contains("Set-Cookie: JSESSIONID="));
                assertTrue(payload.contains("; HttpOnly"));
                assertTrue(payload.contains("; SameSite=Lax"));
        }

        @Test
        public void testSessionCookieResolvesExistingSessionAcrossRequests() {
                InMemorySessionManager sessionManager = new InMemorySessionManager(SessionConfig.defaults());

                DefaultHttpServletRequest firstRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                HttpSession firstSession = firstRequest.getSession(true);
                firstSession.setAttribute("counter", 1);

                DefaultHttpServletRequest secondRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: JSESSIONID=" + firstSession.getId() + "\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);

                HttpSession secondSession = secondRequest.getSession(false);
                assertNotNull(secondSession);
                assertEquals(firstSession.getId(), secondSession.getId());
                assertFalse(secondSession.isNew());
                assertEquals(1, secondSession.getAttribute("counter"));
        }

        @Test
        public void testInvalidatedSessionEmitsDeleteCookie() {
                InMemorySessionManager sessionManager = new InMemorySessionManager(SessionConfig.defaults());

                DefaultHttpServletRequest firstRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                HttpSession firstSession = firstRequest.getSession(true);
                String sessionId = firstSession.getId();
                firstSession.invalidate();

                DefaultHttpServletRequest secondRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: JSESSIONID=" + sessionId + "\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                DefaultHttpServletResponse response = new DefaultHttpServletResponse(256);

                assertNull(secondRequest.getSession(false));
                secondRequest.applySessionToResponse(response, false);
                response.getWriter().print("ok");
                String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
                assertTrue(payload.contains("Set-Cookie: JSESSIONID="));
                assertTrue(payload.contains("; Max-Age=0"));
        }

        @Test
        public void testInvalidatedSessionEmitsDeleteCookieWithoutSessionLookup() {
                InMemorySessionManager sessionManager = new InMemorySessionManager(SessionConfig.defaults());

                DefaultHttpServletRequest firstRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                HttpSession firstSession = firstRequest.getSession(true);
                String sessionId = firstSession.getId();
                firstSession.invalidate();

                DefaultHttpServletRequest secondRequest = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "Cookie: JSESSIONID=" + sessionId + "\r\n"
                                                + "\r\n",
                                RequestLimits.defaults(32 * 1024),
                                sessionManager);
                DefaultHttpServletResponse response = new DefaultHttpServletResponse(256);

                // Do not call getSession(false); applySessionToResponse should still emit
                // a delete cookie for stale identifiers.
                secondRequest.applySessionToResponse(response, false);
                response.getWriter().print("ok");
                String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
                assertTrue(payload.contains("Set-Cookie: JSESSIONID="));
                assertTrue(payload.contains("; Max-Age=0"));
        }

        @Test(expected = IllegalStateException.class)
        public void testStartAsyncThrowsWhenAsyncIsNotConfigured() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                request.startAsync();
        }

        @Test
        public void testStartAsyncMarksRequestAndCompleteClearsState() {
                DefaultHttpServletRequest request = parseRequest(
                                "GET / HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "\r\n");

                request.configureAsyncContextFactory(() -> new AsyncContext() {
                        @Override
                        public void complete() {
                                request.markAsyncCompleted();
                        }

                        @Override
                        public void dispatch() {
                        }

                        @Override
                        public void dispatch(String path) {
                        }

                        @Override
                        public void addListener(com.fastjava.servlet.AsyncListener listener) {
                        }

                        @Override
                        public void setTimeout(long timeoutMillis) {
                        }

                        @Override
                        public long getTimeout() {
                                return 0;
                        }
                });

                AsyncContext asyncContext = request.startAsync();
                assertTrue(request.isAsyncStarted());

                asyncContext.complete();
                assertFalse(request.isAsyncStarted());
        }

        private static DefaultHttpServletRequest parseRequest(String rawRequest) {
                return parseRequest(rawRequest, RequestLimits.defaults(32 * 1024));
        }

        private static DefaultHttpServletRequest parseRequest(String rawRequest, RequestLimits limits) {
                return parseRequest(rawRequest, limits, null);
        }

        private static DefaultHttpServletRequest parseRequest(String rawRequest, RequestLimits limits,
                        InMemorySessionManager sessionManager) {
                byte[] data = rawRequest.getBytes(StandardCharsets.UTF_8);
                ParsedHttpRequest parsed = HttpRequestParser.parse(data, data.length);
                assertNotNull(parsed);
                return new DefaultHttpServletRequest(parsed, "127.0.0.1", 12345, 8080, limits, sessionManager);
        }
}