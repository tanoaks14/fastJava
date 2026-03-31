package com.fastjava.http.simd;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class SIMDByteScannerTest {

    @Test
    public void testFindCRLFAndDoubleCRLF() {
        byte[] data = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        int firstCrlf = SIMDByteScanner.findCRLF(data, 0, data.length);
        int headerEnd = SIMDByteScanner.findDoubleCRLF(data, 0, data.length);

        assertTrue(firstCrlf > 0);
        assertTrue(headerEnd > firstCrlf);
    }

    @Test
    public void testFindCRLFNotFound() {
        byte[] data = "abcdef".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, SIMDByteScanner.findCRLF(data, 0, data.length));
        assertEquals(-1, SIMDByteScanner.findDoubleCRLF(data, 0, data.length));
    }

    @Test
    public void testFindAllOccurrencesExpandsResultArray() {
        byte[] data = new byte[200];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index % 2 == 0 ? 'x' : 'y');
        }

        int[] positions = SIMDByteScanner.findAllOccurrences(data, (byte) 'x', 0, data.length);
        assertEquals(100, positions.length);
        assertEquals(0, positions[0]);
        assertEquals(198, positions[99]);
    }

    @Test
    public void testIndexOfByteFindsExpectedPosition() {
        byte[] data = "abc,def,ghi".getBytes(StandardCharsets.US_ASCII);
        assertEquals(3, SIMDByteScanner.indexOfByte(data, 0, data.length, (byte) ','));
        assertEquals(-1, SIMDByteScanner.indexOfByte(data, 0, data.length, (byte) ';'));
    }

    @Test
    public void testReplaceByteReplacesAllTargets() {
        byte[] source = "a\\b\\c".getBytes(StandardCharsets.US_ASCII);
        byte[] replaced = SIMDByteScanner.replaceByte(source, (byte) '\\', (byte) '/');
        assertEquals("a/b/c", new String(replaced, StandardCharsets.US_ASCII));
        assertEquals("a\\b\\c", new String(source, StandardCharsets.US_ASCII));
    }

    @Test
    public void testBytesEqual() {
        byte[] data = "GET /hello HTTP/1.1".getBytes(StandardCharsets.US_ASCII);

        assertTrue(SIMDByteScanner.bytesEqual(data, 0, "GET".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(SIMDByteScanner.bytesEqual(data, 0, "POST".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(SIMDByteScanner.bytesEqual(data, data.length - 1, "GET".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testStartsWith() {
        byte[] data = "application/octet-stream".getBytes(StandardCharsets.US_ASCII);
        assertTrue(
                SIMDByteScanner.startsWith(data, 0, data.length, "application/".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(SIMDByteScanner.startsWith(data, 0, data.length, "image/".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testEndsWith() {
        byte[] data = "/assets/main.bundle.js".getBytes(StandardCharsets.US_ASCII);
        assertTrue(SIMDByteScanner.endsWith(data, 0, data.length, ".js".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(SIMDByteScanner.endsWith(data, 0, data.length, ".css".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testCountHighBitSetBytes() {
        byte[] data = new byte[] { 1, 2, -1, -128, 8, 9, 127, -3 };
        assertEquals(3, SIMDByteScanner.countHighBitSetBytes(data, 0, data.length));
        assertEquals(2, SIMDByteScanner.countHighBitSetBytes(data, 2, 4));
    }

    @Test
    public void testTrimStartAndTrimEnd() {
        byte[] data = "   \t value  \r\n".getBytes(StandardCharsets.US_ASCII);

        int start = SIMDByteScanner.trimStart(data, 0, data.length);
        int end = SIMDByteScanner.trimEnd(data, start, data.length);

        String trimmed = new String(data, start, end - start, StandardCharsets.US_ASCII);
        assertEquals("value", trimmed);
    }

    @Test
    public void testFindBoundaryLineFindsNormalBoundary() {
        byte[] body = ("--boundary123\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n"
                + "\r\n"
                + "value\r\n"
                + "--boundary123--\r\n").getBytes(StandardCharsets.US_ASCII);

        byte[] delimiter = "--boundary123".getBytes(StandardCharsets.US_ASCII);
        int first = SIMDByteScanner.findBoundaryLine(body, 0, body.length, delimiter);
        assertEquals(0, first);

        int second = SIMDByteScanner.findBoundaryLine(body, first + delimiter.length, body.length, delimiter);
        assertTrue(second > first);
    }

    @Test
    public void testFindBoundaryLineRejectsMidLineCandidate() {
        byte[] body = "abc--boundary123\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] delimiter = "--boundary123".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, SIMDByteScanner.findBoundaryLine(body, 0, body.length, delimiter));
    }

    @Test
    public void testFindBoundaryLineWithNonZeroOffset() {
        byte[] prefix = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] body = ("--boundary123\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n"
                + "\r\n"
                + "value\r\n"
                + "--boundary123--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);

        byte[] delimiter = "--boundary123".getBytes(StandardCharsets.US_ASCII);
        int boundary = SIMDByteScanner.findBoundaryLine(all, prefix.length, all.length, delimiter);
        assertEquals(prefix.length, boundary);
    }

    @Test
    public void testFindBoundaryLineSkipsNoisyHyphenCandidates() {
        String noise = "-a--b---c----d-----e------f".repeat(40);
        byte[] body = ("--boundary123\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n"
                + "\r\n"
                + noise + "\r\n"
                + "--boundary123--\r\n").getBytes(StandardCharsets.US_ASCII);

        byte[] delimiter = "--boundary123".getBytes(StandardCharsets.US_ASCII);
        int first = SIMDByteScanner.findBoundaryLine(body, 0, body.length, delimiter);
        assertEquals(0, first);

        int second = SIMDByteScanner.findBoundaryLine(body, first + delimiter.length, body.length, delimiter);
        assertTrue(second > 0);
    }
}