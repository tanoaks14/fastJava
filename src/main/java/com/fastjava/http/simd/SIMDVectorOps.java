package com.fastjava.http.simd;

import jdk.incubator.vector.*;

/**
 * SIMD-optimized vector operations for response building and buffer
 * manipulation.
 * Provides batch operations for faster response generation.
 */
public class SIMDVectorOps {

    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    /**
     * Batch fill buffer with a byte pattern using SIMD.
     * Much faster than looping for large buffers.
     */
    public static void batchFill(byte[] buffer, int offset, int length, byte value) {
        ByteVector pattern = ByteVector.broadcast(BYTE_SPECIES, value);
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        // Vectorized fill
        for (; i <= offset + length - vectorLength; i += vectorLength) {
            pattern.intoArray(buffer, i);
        }

        // Remaining scalar bytes
        for (; i < offset + length; i++) {
            buffer[i] = value;
        }
    }

    /**
     * Batch copy multiple byte arrays into a target buffer (header + body pattern).
     */
    public static int batchCopy(byte[] target, int offset, byte[]... sources) {
        int pos = offset;
        for (byte[] src : sources) {
            if (src != null) {
                System.arraycopy(src, 0, target, pos, src.length);
                pos += src.length;
            }
        }
        return pos;
    }

    /**
     * Vectorized byte count - count occurrences of a byte value.
     */
    public static int countOccurrences(byte[] buffer, int offset, int length, byte value) {
        int count = 0;
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        ByteVector searchVec = ByteVector.broadcast(BYTE_SPECIES, value);

        // Vectorized counting
        for (; i <= offset + length - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            VectorMask<Byte> mask = vec.eq(searchVec);
            count += mask.trueCount();
        }

        // Remaining scalar bytes
        for (; i < offset + length; i++) {
            if (buffer[i] == value) {
                count++;
            }
        }

        return count;
    }

    /**
     * Vectorized comparison of two byte ranges.
     */
    public static boolean rangesEqual(byte[] buf1, int off1, byte[] buf2, int off2, int length) {
        int i = 0;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= length - vectorLength; i += vectorLength) {
            ByteVector v1 = ByteVector.fromArray(BYTE_SPECIES, buf1, off1 + i);
            ByteVector v2 = ByteVector.fromArray(BYTE_SPECIES, buf2, off2 + i);

            if (!v1.eq(v2).allTrue()) {
                return false;
            }
        }

        for (; i < length; i++) {
            if (buf1[off1 + i] != buf2[off2 + i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Vectorized XOR for simple checksums/hashing.
     */
    public static long xorChecksum(byte[] buffer, int offset, int length) {
        int i = offset;
        int end = offset + length;
        int vectorLength = BYTE_SPECIES.length();
        ByteVector accumulator = ByteVector.zero(BYTE_SPECIES);

        for (; i <= end - vectorLength; i += vectorLength) {
            ByteVector vector = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            accumulator = accumulator.lanewise(VectorOperators.XOR, vector);
        }

        long checksum = 0;
        for (int lane = 0; lane < vectorLength; lane++) {
            checksum ^= (accumulator.lane(lane) & 0xFFL);
        }

        for (; i < end; i++) {
            checksum ^= (buffer[i] & 0xFFL);
        }

        return checksum;
    }

    /**
     * Find minimum/maximum byte value in range.
     */
    public static byte findMin(byte[] buffer, int offset, int length) {
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        ByteVector minVec = ByteVector.broadcast(BYTE_SPECIES, Byte.MAX_VALUE);

        for (; i <= offset + length - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);
            minVec = vec.min(minVec);
        }

        byte min = Byte.MAX_VALUE;
        for (int j = 0; j < vectorLength; j++) {
            min = (byte) Math.min(min, minVec.lane(j));
        }

        for (; i < offset + length; i++) {
            min = (byte) Math.min(min, buffer[i]);
        }

        return min;
    }

    /**
     * Batch convert ASCII uppercase to lowercase using SIMD.
     */
    public static void toLowerCase(byte[] buffer, int offset, int length) {
        int i = offset;
        int vectorLength = BYTE_SPECIES.length();

        for (; i <= offset + length - vectorLength; i += vectorLength) {
            ByteVector vec = ByteVector.fromArray(BYTE_SPECIES, buffer, i);

            // Check if within A-Z range and convert to lowercase
            ByteVector upper_A = ByteVector.broadcast(BYTE_SPECIES, (byte) 'A');
            ByteVector upper_Z = ByteVector.broadcast(BYTE_SPECIES, (byte) 'Z');
            ByteVector diff = ByteVector.broadcast(BYTE_SPECIES, (byte) 32);

            VectorMask<Byte> inRange = vec.compare(VectorOperators.GE, upper_A)
                    .and(vec.compare(VectorOperators.LE, upper_Z));
            ByteVector result = vec.add(diff, inRange);
            result.intoArray(buffer, i);
        }

        // Remaining scalar bytes
        for (; i < offset + length; i++) {
            if (buffer[i] >= 'A' && buffer[i] <= 'Z') {
                buffer[i] = (byte) (buffer[i] + 32);
            }
        }
    }

    /**
     * SIMD-accelerated trim all whitespace from a range.
     */
    public static int trimAllWhitespace(byte[] buffer, int offset, int length) {
        byte[] space = new byte[length];
        int count = 0;

        for (int i = 0; i < length; i++) {
            byte b = buffer[offset + i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                space[count++] = b;
            }
        }

        System.arraycopy(space, 0, buffer, offset, count);
        return count;
    }
}
