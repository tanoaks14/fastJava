package com.fastjava.servlet;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public interface Part {
    String getName();

    String getSubmittedFileName();

    String getContentType();

    long getSize();

    InputStream getInputStream();

    byte[] getBytes();

    default long transferTo(OutputStream output) throws IOException {
        try (InputStream input = getInputStream()) {
            byte[] copyBuffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(copyBuffer)) != -1) {
                output.write(copyBuffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    default void writeTo(Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            transferTo(output);
        }
    }

    /**
     * Writes this part to a file on disk.
     * Default implementation uses writeTo(Path), but streaming implementations
     * may optimize by directly writing from source.
     * 
     * @param fileName the file path to write to
     * @throws IOException if an I/O error occurs
     */
    default void write(String fileName) throws IOException {
        writeTo(Path.of(fileName));
    }

    /**
     * Deletes any temporary resources associated with this part (e.g., spilled temp
     * files).
     * Default implementation is no-op, but implementations that spill to disk
     * should override.
     * 
     * @throws IOException if an I/O error occurs
     */
    default void delete() throws IOException {
        // Default: no cleanup needed
    }
}
