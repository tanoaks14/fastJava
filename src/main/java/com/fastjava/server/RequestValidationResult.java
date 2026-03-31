package com.fastjava.server;

public record RequestValidationResult(int statusCode, String message) {

    public static RequestValidationResult none() {
        return new RequestValidationResult(0, null);
    }

    public boolean hasError() {
        return statusCode != 0;
    }
}