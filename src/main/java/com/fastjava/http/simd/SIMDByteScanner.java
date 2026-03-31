package com.fastjava.http.simd;

import jdk.incubator.vector.*;

/**
 * SIMD-optimized HTTP header and request parsing using Java Vector API.
 * Uses vectorized byte scanning for maximum performance.
 * 
 * Key optimizations:
 * - Vectorized search for \r\n line delimiters
 * - Batch header scanning
 * - Minimum allocations and indirection
 */
public class SIMDByteScanner {

    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final byte CR = '\r';
    private static final byte LF = '\n';
    private static final byte ASCII_UPPER_A = 'A';
    private static final byte ASCII_UPPER_Z = 'Z';
    private static final byte ASCII_CASE_DELTA = 32;

    /**
     * Find the first occurrence of \r\n in a byte array using SIMD.
     * Vectorized scanning for line delimiters - much faster than scalar search.
     */
    public static int findCRLF(byte[] buffer, int offset, int limit) {
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        // Vectorized loop - process multiple bytes at once
        for (; i <= limit - vectorLength - 1; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> crMask = vec.eq(CR);

            if (crMask.anyTrue()) {
                // Found potential CR, check scalar for \r\n
                for (int j = i; j < i + vectorLength && j < limit - 1; j++) {
                    if (buffer[j] == CR && buffer[j + 1] == LF) {
                        return j;
                    }
                }
            }
        }

        // Remaining scalar bytes
        for (; i < limit - 1; i++) {
            if (buffer[i] == CR && buffer[i + 1] == LF) {
                return i;
            }
        }
        return -1;
    }

    public static int findDoubleCRLF(byte[] buffer, int offset, int limit) {
        int searchOffset = offset;
        while (searchOffset < limit - 3) {
            int crlfPos = findCRLF(buffer, searchOffset, limit);
            if (crlfPos == -1) {
                return -1;
            }
            if (crlfPos + 3 < limit && buffer[crlfPos + 2] == CR && buffer[crlfPos + 3] == LF) {
                return crlfPos;
            }
            searchOffset = crlfPos + 2;
        }
        return -1;
    }

    /**
     * Find multipart boundary lines in the form: [CRLF]--boundary[--]CRLF.
     * Returns the index of the first dash in "--boundary".
     */
    public static int findBoundaryLine(byte[] buffer, int offset, int limit, byte[] delimiter) {
        if (buffer == null || delimiter == null || delimiter.length == 0 || offset < 0 || offset >= limit) {
            return -1;
        }

        int vectorLength = BYTE_SPECIES.length();
        int i = offset;
        byte dash = '-';

        for (; i <= limit - vectorLength - 1; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> dashMask = vec.eq(dash);
            if (!dashMask.anyTrue()) {
                continue;
            }

            // Multi-byte prefilter: keep only lanes that start with "--".
            ByteVector nextVec = ByteVector.fromArray(BYTE_SPECIES, buffer, i + 1);
            VectorMask<Byte> doubleDashMask = dashMask.and(nextVec.eq(dash));
            if (!doubleDashMask.anyTrue()) {
                continue;
            }

            for (int lane = 0; lane < vectorLength; lane++) {
                if (!doubleDashMask.laneIsSet(lane)) {
                    continue;
                }
                int candidate = i + lane;
                int match = verifyBoundaryCandidate(buffer, offset, limit, delimiter, candidate);
                if (match >= 0) {
                    return match;
                }
            }
        }

        for (; i < limit; i++) {
            if (buffer[i] != dash) {
                continue;
            }
            int match = verifyBoundaryCandidate(buffer, offset, limit, delimiter, i);
            if (match >= 0) {
                return match;
            }
        }

        return -1;
    }

    private static int verifyBoundaryCandidate(byte[] buffer, int offset, int limit, byte[] delimiter, int candidate) {
        if (candidate + 1 >= limit || buffer[candidate + 1] != '-') {
            return -1;
        }

        if (candidate + delimiter.length > limit || !bytesEqual(buffer, candidate, delimiter)) {
            return -1;
        }

        boolean isStartOfLine = candidate == offset
                || (candidate >= 2 && buffer[candidate - 2] == CR && buffer[candidate - 1] == LF);
        if (!isStartOfLine) {
            return -1;
        }

        int next = candidate + delimiter.length;
        if (next + 1 < limit && buffer[next] == CR && buffer[next + 1] == LF) {
            return candidate;
        }
        if (next + 3 < limit && buffer[next] == '-' && buffer[next + 1] == '-'
                && buffer[next + 2] == CR && buffer[next + 3] == LF) {
            return candidate;
        }
        return -1;
    }

    /**
     * Find all occurrences of a byte pattern in buffer (for header scanning).
     * Returns array of positions.
     */
    public static int[] findAllOccurrences(byte[] buffer, byte target, int offset, int limit) {
        int[] positions = new int[64]; // Pre-allocated, resize if needed
        int count = 0;

        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        // Vectorized search
        for (; i <= limit - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> mask = vec.eq(target);

            if (mask.anyTrue()) {
                // Check each matching position
                for (int j = 0; j < vectorLength; j++) {
                    if (mask.laneIsSet(j) && i + j < limit) {
                        if (count >= positions.length) {
                            positions = expandArray(positions);
                        }
                        positions[count++] = i + j;
                    }
                }
            }
        }

        // Remaining scalar bytes
        for (; i < limit; i++) {
            if (buffer[i] == target) {
                if (count >= positions.length) {
                    positions = expandArray(positions);
                }
                positions[count++] = i;
            }
        }

        int[] result = new int[count];
        System.arraycopy(positions, 0, result, 0, count);
        return result;
    }

    /**
     * Find first index of target in buffer[offset, limit), returns -1 when absent.
     */
    public static int indexOfByte(byte[] buffer, int offset, int limit, byte target) {
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= limit - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> mask = vec.eq(target);
            if (mask.anyTrue()) {
                for (int lane = 0; lane < vectorLength; lane++) {
                    if (mask.laneIsSet(lane) && i + lane < limit) {
                        return i + lane;
                    }
                }
            }
        }

        for (; i < limit; i++) {
            if (buffer[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns a copy where all occurrences of target are replaced with replacement.
     */
    public static byte[] replaceByte(byte[] source, byte target, byte replacement) {
        byte[] result = source.clone();
        int i = 0;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= result.length - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, result, i);
            VectorMask<Byte> targetMask = vec.eq(target);
            if (targetMask.anyTrue()) {
                vec.blend(replacement, targetMask).intoArray(result, i);
            }
        }

        for (; i < result.length; i++) {
            if (result[i] == target) {
                result[i] = replacement;
            }
        }
        return result;
    }

    /**
     * Batch compare multiple byte sequences using SIMD.
     * Useful for comparing HTTP methods (GET, POST, PUT, etc.)
     */
    public static boolean bytesEqual(byte[] buffer, int offset, byte[] pattern) {
        if (offset + pattern.length > buffer.length) {
            return false;
        }

        int i = 0;
        int vectorLength = BYTE_SPECIES.length();

        // Vectorized comparison
        for (; i <= pattern.length - vectorLength; i += vectorLength) {
            ByteVector bufVec = ByteVector.fromArray(BYTE_SPECIES, buffer, offset + i);
            ByteVector patVec = ByteVector.fromArray(BYTE_SPECIES, pattern, i);

            if (!bufVec.eq(patVec).allTrue()) {
                return false;
            }
        }

        // Remaining scalar bytes
        for (; i < pattern.length; i++) {
            if (buffer[offset + i] != pattern[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true when buffer[offset,limit) starts with the provided pattern.
     */
    public static boolean startsWith(byte[] buffer, int offset, int limit, byte[] pattern) {
        int available = Math.max(0, limit - offset);
        if (pattern.length > available) {
            return false;
        }
        return bytesEqual(buffer, offset, pattern);
    }

    /**
     * Returns true when buffer[offset,limit) ends with the provided pattern.
     */
    public static boolean endsWith(byte[] buffer, int offset, int limit, byte[] pattern) {
        int available = Math.max(0, limit - offset);
        if (pattern.length > available) {
            return false;
        }
        int start = limit - pattern.length;
        return bytesEqual(buffer, start, pattern);
    }

    /**
     * Count bytes with the high bit set (unsigned value >= 128).
     */
    public static int countHighBitSetBytes(byte[] buffer, int offset, int length) {
        int end = offset + Math.max(0, length);
        int count = 0;
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= end - vectorLength; i += vectorLength) {
            ByteVector vector = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> highBitMask = vector.compare(VectorOperators.LT, (byte) 0);
            count += highBitMask.trueCount();
        }

        for (; i < end; i++) {
            if (buffer[i] < 0) {
                count++;
            }
        }

        return count;
    }

    /**
     * Returns a lowercase ASCII copy of the source byte range.
     */
    public static byte[] toLowercaseAscii(byte[] source, int start, int end) {
        int length = Math.max(0, end - start);
        byte[] lowered = new byte[length];
        lowercaseAscii(source, start, end, lowered, 0);
        return lowered;
    }

    /**
     * Lowercase ASCII letters from source[start,end) into destination.
     */
    public static void lowercaseAscii(byte[] source, int start, int end, byte[] destination, int destinationOffset) {
        int length = Math.max(0, end - start);
        if (length == 0) {
            return;
        }

        int vectorLength = BYTE_SPECIES.length();
        int i = 0;
        for (; i <= length - vectorLength; i += vectorLength) {
            ByteVector vector = ByteVector.fromArray(BYTE_SPECIES, source, start + i);
            VectorMask<Byte> upperCaseMask = vector.compare(VectorOperators.GE, ASCII_UPPER_A)
                    .and(vector.compare(VectorOperators.LE, ASCII_UPPER_Z));
            ByteVector lowered = vector.add(ASCII_CASE_DELTA);
            ByteVector normalized = vector.blend(lowered, upperCaseMask);
            normalized.intoArray(destination, destinationOffset + i);
        }

        for (; i < length; i++) {
            byte value = source[start + i];
            if (value >= ASCII_UPPER_A && value <= ASCII_UPPER_Z) {
                value = (byte) (value + ASCII_CASE_DELTA);
            }
            destination[destinationOffset + i] = value;
        }
    }

    /**
     * Trim whitespace from byte range using SIMD detection.
     */
    public static int trimStart(byte[] buffer, int start, int end) {
        int i = start;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= end - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> nonSpace = vec.compare(VectorOperators.NE, (byte) ' ')
                    .and(vec.compare(VectorOperators.NE, (byte) '\t'));

            if (nonSpace.anyTrue()) {
                // Found non-whitespace, scan to exact position
                for (int j = i; j < i + vectorLength; j++) {
                    if (buffer[j] != ' ' && buffer[j] != '\t') {
                        return j;
                    }
                }
            }
        }

        for (; i < end; i++) {
            if (buffer[i] != ' ' && buffer[i] != '\t') {
                return i;
            }
        }
        return end;
    }

    /**
     * Trim end whitespace.
     */
    public static int trimEnd(byte[] buffer, int start, int end) {
        for (int i = end - 1; i >= start; i--) {
            if (buffer[i] != ' ' && buffer[i] != '\t' && buffer[i] != '\r' && buffer[i] != '\n') {
                return i + 1;
            }
        }
        return start;
    }

    // Utility methods
    private static int[] expandArray(int[] arr) {
        int[] newArr = new int[arr.length * 2];
        System.arraycopy(arr, 0, newArr, 0, arr.length);
        return newArr;
    }
}
