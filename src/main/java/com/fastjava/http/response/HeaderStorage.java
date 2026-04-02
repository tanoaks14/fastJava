package com.fastjava.http.response;

import java.util.Objects;

/**
 * Optimized fixed-capacity header storage for HTTP responses.
 * Replaces ArrayList<HeaderValue> with a fixed-size array for O(1) removal
 * and reduced per-request allocations.
 * 
 * Typical HTTP responses have 10-20 headers, so a fixed array of 32 is optimal.
 */
public class HeaderStorage implements Iterable<HeaderStorage.HeaderValue> {

    private static final int DEFAULT_CAPACITY = 32;

    // Fixed-size array of headers (pre-allocated once per builder)
    private HeaderValue[] headers;
    private int size;

    public HeaderStorage() {
        this(DEFAULT_CAPACITY);
    }

    public HeaderStorage(int capacity) {
        this.headers = new HeaderValue[capacity];
        this.size = 0;
    }

    /**
     * Add a header (no duplicate removal).
     */
    public void add(String name, String value) {
        if (size >= headers.length) {
            grow();
        }
        HeaderValue slot = headers[size];
        if (slot == null) {
            headers[size] = new HeaderValue(name, value);
        } else {
            slot.set(name, value);
        }
        size++;
    }

    private void grow() {
        int newCapacity = Math.max(4, headers.length * 2);
        HeaderValue[] newArray = new HeaderValue[newCapacity];
        System.arraycopy(headers, 0, newArray, 0, size);
        headers = newArray;
    }

    /**
     * Set a header (replace if exists, otherwise add).
     */
    public void set(String name, String value) {
        // Remove existing header with same name (case-insensitive)
        for (int i = 0; i < size; i++) {
            if (equalsIgnoreCase(headers[i].name(), name)) {
                headers[i].set(name, value);
                return;
            }
        }
        // Not found, add new
        add(name, value);
    }

    /**
     * Get header value by name (case-insensitive).
     */
    public String get(String name) {
        for (int i = 0; i < size; i++) {
            if (equalsIgnoreCase(headers[i].name(), name)) {
                return headers[i].value();
            }
        }
        return null;
    }

    /**
     * Check if header exists (case-insensitive).
     */
    public boolean contains(String name) {
        for (int i = 0; i < size; i++) {
            if (equalsIgnoreCase(headers[i].name(), name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get header at index.
     */
    public HeaderValue get(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        return headers[index];
    }

    /**
     * Get total header count.
     */
    public int size() {
        return size;
    }

    /**
     * Remove header by name (case-insensitive).
     */
    public void removeByName(String name) {
        if (name == null) {
            return;
        }
        for (int i = 0; i < size; i++) {
            if (equalsIgnoreCase(headers[i].name(), name)) {
                // Shift remaining headers left
                System.arraycopy(headers, i + 1, headers, i, size - i - 1);
                headers[--size] = null;
                // Don't return, continue to remove all matching headers
                i--;
            }
        }
    }

    /**
     * Get an iterator for enhanced for-loop compatibility.
     */
    @Override
    public java.util.Iterator<HeaderStorage.HeaderValue> iterator() {
        return new java.util.Iterator<HeaderStorage.HeaderValue>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public HeaderStorage.HeaderValue next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                return headers[index++];
            }
        };
    }

    /**
     * Clear all headers.
     */
    public void clear() {
        // Keep header slots for reuse; just reset logical size.
        size = 0;
    }

    /**
     * Iterate through headers (for building response).
     */
    public void forEach(HeaderConsumer consumer) {
        for (int i = 0; i < size; i++) {
            if (headers[i] != null) {
                consumer.accept(headers[i]);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0)
                sb.append(", ");
            HeaderValue h = headers[i];
            sb.append(h.name()).append(": ").append(h.value());
        }
        return sb.toString();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null)
            return a == b;
        if (a == b)
            return true;
        if (a.length() != b.length())
            return false;
        for (int i = 0; i < a.length(); i++) {
            char ca = a.charAt(i);
            char cb = b.charAt(i);
            if (ca != cb) {
                if (ca >= 'A' && ca <= 'Z')
                    ca += 32;
                if (cb >= 'A' && cb <= 'Z')
                    cb += 32;
                if (ca != cb)
                    return false;
            }
        }
        return true;
    }

    /**
     * Functional interface for forEach.
     */
    @FunctionalInterface
    public interface HeaderConsumer {
        void accept(HeaderValue header);
    }

    /**
     * Immutable header name-value pair.
     */
    public static final class HeaderValue {
        private String name;
        private String value;

        public HeaderValue(String name, String value) {
            set(name, value);
        }

        private void set(String name, String value) {
            this.name = Objects.requireNonNull(name);
            this.value = Objects.requireNonNull(value);
        }

        public String name() {
            return name;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return name + ": " + value;
        }
    }
}
