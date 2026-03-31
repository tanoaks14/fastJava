package com.fastjava.websocket;

import com.fastjava.websocket.annotation.OnClose;
import com.fastjava.websocket.annotation.OnError;
import com.fastjava.websocket.annotation.OnMessage;
import com.fastjava.websocket.annotation.OnOpen;
import com.fastjava.websocket.annotation.PathParam;
import com.fastjava.websocket.annotation.WebSocketEndpoint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

public final class WebSocketEndpointMetadata {

    private final String path;
    private final String[] subprotocols;
    private final Class<?> endpointClass;
    private final Constructor<?> constructor;
    private final Method onOpen;
    private final Method onMessage;
    private final Method onClose;
    private final Method onError;
    private final Class<?> onMessagePayloadType;

    private WebSocketEndpointMetadata(
            String path,
            String[] subprotocols,
            Class<?> endpointClass,
            Constructor<?> constructor,
            Method onOpen,
            Method onMessage,
            Method onClose,
            Method onError,
            Class<?> onMessagePayloadType) {
        this.path = path;
        this.subprotocols = subprotocols;
        this.endpointClass = endpointClass;
        this.constructor = constructor;
        this.onOpen = onOpen;
        this.onMessage = onMessage;
        this.onClose = onClose;
        this.onError = onError;
        this.onMessagePayloadType = onMessagePayloadType;
    }

    public static WebSocketEndpointMetadata fromClass(Class<?> endpointClass) {
        WebSocketEndpoint annotation = endpointClass.getAnnotation(WebSocketEndpoint.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Missing @WebSocketEndpoint annotation on " + endpointClass.getName());
        }
        String path = annotation.path();
        if (path == null || path.isBlank() || path.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "WebSocket endpoint path must start with '/': " + endpointClass.getName());
        }
        String[] subprotocols = Arrays.stream(annotation.subprotocols())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);

        Constructor<?> constructor;
        try {
            constructor = endpointClass.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException noSuchMethodException) {
            throw new IllegalArgumentException(
                    "WebSocket endpoint must have a no-arg constructor: " + endpointClass.getName(),
                    noSuchMethodException);
        }

        Method onOpen = null;
        Method onMessage = null;
        Method onClose = null;
        Method onError = null;
        Class<?> messagePayloadType = null;

        for (Method method : endpointClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnOpen.class)) {
                onOpen = ensureSingle("@OnOpen", onOpen, method);
                validateOnOpen(method);
                method.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnMessage.class)) {
                onMessage = ensureSingle("@OnMessage", onMessage, method);
                messagePayloadType = validateOnMessage(method);
                method.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnClose.class)) {
                onClose = ensureSingle("@OnClose", onClose, method);
                validateOnClose(method);
                method.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnError.class)) {
                onError = ensureSingle("@OnError", onError, method);
                validateOnError(method);
                method.setAccessible(true);
            }
        }

        if (onMessage == null) {
            throw new IllegalArgumentException("WebSocket endpoint must declare exactly one @OnMessage method: "
                    + endpointClass.getName());
        }

        return new WebSocketEndpointMetadata(path, subprotocols, endpointClass, constructor, onOpen, onMessage,
                onClose, onError, messagePayloadType);
    }

    private static Method ensureSingle(String annotationName, Method existing, Method candidate) {
        if (existing != null) {
            throw new IllegalArgumentException("Only one " + annotationName + " method is allowed per endpoint");
        }
        return candidate;
    }

    private static void validateOnOpen(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 1 || params[0] != WebSocketSession.class) {
            throw new IllegalArgumentException("@OnOpen method must start with (WebSocketSession)");
        }
        validatePathParamTail(method, 1);
    }

    private static Class<?> validateOnMessage(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 2 || params[0] != WebSocketSession.class) {
            throw new IllegalArgumentException(
                    "@OnMessage method must start with (WebSocketSession, String|byte[])");
        }

        Class<?> payloadType = params[1];
        if (payloadType != String.class && payloadType != byte[].class) {
            throw new IllegalArgumentException(
                    "@OnMessage method payload must be String or byte[]");
        }
        validatePathParamTail(method, 2);
        return payloadType;
    }

    private static void validateOnClose(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 1 || params[0] != WebSocketSession.class) {
            throw new IllegalArgumentException("@OnClose method must start with (WebSocketSession)");
        }
        validatePathParamTail(method, 1);
    }

    private static void validateOnError(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 2 || params[0] != WebSocketSession.class || !Throwable.class.isAssignableFrom(params[1])) {
            throw new IllegalArgumentException("@OnError method must start with (WebSocketSession, Throwable)");
        }
        validatePathParamTail(method, 2);
    }

    private static void validatePathParamTail(Method method, int startIndex) {
        Parameter[] parameters = method.getParameters();
        for (int index = startIndex; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            if (parameter.getType() != String.class) {
                throw new IllegalArgumentException("@PathParam parameters must be String: " + method.getName());
            }
            PathParam pathParam = parameter.getAnnotation(PathParam.class);
            if (pathParam == null || pathParam.value() == null || pathParam.value().isBlank()) {
                throw new IllegalArgumentException(
                        "Path parameter arguments must declare @PathParam with non-blank value: " + method.getName());
            }
        }
    }

    public String path() {
        return path;
    }

    public String[] subprotocols() {
        return Arrays.copyOf(subprotocols, subprotocols.length);
    }

    public Class<?> endpointClass() {
        return endpointClass;
    }

    public Constructor<?> constructor() {
        return constructor;
    }

    public Method onOpen() {
        return onOpen;
    }

    public Method onMessage() {
        return onMessage;
    }

    public Method onClose() {
        return onClose;
    }

    public Method onError() {
        return onError;
    }

    public Class<?> onMessagePayloadType() {
        return onMessagePayloadType;
    }
}
