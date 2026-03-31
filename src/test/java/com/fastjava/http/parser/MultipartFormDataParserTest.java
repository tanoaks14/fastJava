package com.fastjava.http.parser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MultipartFormDataParserTest {

        @Test
        public void testParseTextParametersSkipsFileParts() {
                String boundary = "----fastjava-parser";
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"name\"\r\n"
                                + "\r\n"
                                + "alice\r\n"
                                + "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "\r\n"
                                + "ignored-file-content\r\n"
                                + "--" + boundary + "--\r\n";

                Map<String, List<String>> values = new LinkedHashMap<>();
                boolean parsed = MultipartFormDataParser.parseTextParameters(
                                bodyText.getBytes(StandardCharsets.UTF_8),
                                "multipart/form-data; boundary=" + boundary,
                                values,
                                false);

                assertTrue(parsed);
                assertEquals("alice", values.get("name").get(0));
                assertFalse(values.containsKey("file"));
        }

        @Test
        public void testParseTextParametersReturnsFalseForMissingBoundary() {
                Map<String, List<String>> values = new LinkedHashMap<>();
                boolean parsed = MultipartFormDataParser.parseTextParameters(
                                "x=y".getBytes(StandardCharsets.UTF_8),
                                "multipart/form-data",
                                values,
                                false);

                assertFalse(parsed);
                assertTrue(values.isEmpty());
        }

        @Test
        public void testSkipExistingKeysHonorsQueryPrecedence() {
                String boundary = "----fastjava-existing";
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"id\"\r\n"
                                + "\r\n"
                                + "fromBody\r\n"
                                + "--" + boundary + "--\r\n";

                Map<String, List<String>> values = new LinkedHashMap<>();
                values.put("id", new ArrayList<>(List.of("fromQuery")));

                boolean parsed = MultipartFormDataParser.parseTextParameters(
                                bodyText.getBytes(StandardCharsets.UTF_8),
                                "multipart/form-data; boundary=" + boundary,
                                values,
                                true);

                assertTrue(parsed);
                assertEquals("fromQuery", values.get("id").get(0));
                assertEquals(1, values.get("id").size());
        }

        @Test
        public void testParseReturnsTextAndFileParts() {
                String boundary = "----fastjava-allparts";
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"title\"\r\n"
                                + "\r\n"
                                + "hello world\r\n"
                                + "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"a.txt\"\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "\r\n"
                                + "file-content\r\n"
                                + "--" + boundary + "--\r\n";

                MultipartFormDataParser.ParsedMultipart parsed = MultipartFormDataParser.parse(
                                bodyText.getBytes(StandardCharsets.UTF_8),
                                "multipart/form-data; boundary=" + boundary);

                assertTrue(parsed.valid());
                assertEquals(2, parsed.parts().size());

                MultipartFormDataParser.ParsedPart textPart = parsed.parts().get(0);
                assertEquals("title", textPart.name());
                assertFalse(textPart.isFilePart());
                assertEquals("hello world", new String(textPart.valueBytes(), StandardCharsets.UTF_8));

                MultipartFormDataParser.ParsedPart filePart = parsed.parts().get(1);
                assertEquals("upload", filePart.name());
                assertTrue(filePart.isFilePart());
                assertEquals("a.txt", filePart.submittedFileName());
                assertEquals("text/plain", filePart.contentType());
                assertEquals("file-content", new String(filePart.valueBytes(), StandardCharsets.UTF_8));
        }

        @Test
        public void testParseSpillsLargeFilePartToDisk() {
                String boundary = "----fastjava-spill";
                String payload = "x".repeat(2048);
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"big.bin\"\r\n"
                                + "Content-Type: application/octet-stream\r\n"
                                + "\r\n"
                                + payload + "\r\n"
                                + "--" + boundary + "--\r\n";

                MultipartFormDataParser.MultipartLimits limits = new MultipartFormDataParser.MultipartLimits(
                                16 * 1024,
                                8 * 1024,
                                128);
                MultipartFormDataParser.ParsedMultipart parsed = MultipartFormDataParser.parse(
                                bodyText.getBytes(StandardCharsets.UTF_8),
                                0,
                                bodyText.getBytes(StandardCharsets.UTF_8).length,
                                "multipart/form-data; boundary=" + boundary,
                                limits);

                assertTrue(parsed.valid());
                assertEquals(1, parsed.parts().size());
                MultipartFormDataParser.ParsedPart filePart = parsed.parts().getFirst();
                assertTrue(filePart.isSpilledToDisk());
                assertEquals(payload.length(), filePart.size());
                assertEquals(payload, new String(filePart.getBytes(), StandardCharsets.UTF_8));
        }

        @Test
        public void testParseRejectsPartExceedingLimit() {
                String boundary = "----fastjava-limits";
                String payload = "x".repeat(512);
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"big.bin\"\r\n"
                                + "Content-Type: application/octet-stream\r\n"
                                + "\r\n"
                                + payload + "\r\n"
                                + "--" + boundary + "--\r\n";

                MultipartFormDataParser.MultipartLimits limits = new MultipartFormDataParser.MultipartLimits(
                                16 * 1024,
                                128,
                                64);
                MultipartFormDataParser.ParsedMultipart parsed = MultipartFormDataParser.parse(
                                bodyText.getBytes(StandardCharsets.UTF_8),
                                0,
                                bodyText.getBytes(StandardCharsets.UTF_8).length,
                                "multipart/form-data; boundary=" + boundary,
                                limits);

                assertFalse(parsed.valid());
        }

        @Test
        public void testParseFromInputStreamAndTransferPart() throws Exception {
                String boundary = "----fastjava-stream";
                String payload = "streamed-content";
                String bodyText = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"upload\"; filename=\"a.txt\"\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "\r\n"
                                + payload + "\r\n"
                                + "--" + boundary + "--\r\n";

                MultipartFormDataParser.ParsedMultipart parsed = MultipartFormDataParser.parse(
                                new ByteArrayInputStream(bodyText.getBytes(StandardCharsets.UTF_8)),
                                "multipart/form-data; boundary=" + boundary,
                                MultipartFormDataParser.MultipartLimits.defaults());

                assertTrue(parsed.valid());
                assertEquals(1, parsed.parts().size());

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                long copied = parsed.parts().getFirst().transferTo(output);
                assertEquals(payload.length(), copied);
                assertEquals(payload, output.toString(StandardCharsets.UTF_8));
        }
}
