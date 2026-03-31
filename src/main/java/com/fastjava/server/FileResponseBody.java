package com.fastjava.server;

import java.nio.file.Path;

public record FileResponseBody(Path path, long offset, long length) {

    public FileResponseBody(Path path, long length) {
        this(path, 0L, length);
    }

    public FileResponseBody {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length cannot be negative");
        }
    }
}
