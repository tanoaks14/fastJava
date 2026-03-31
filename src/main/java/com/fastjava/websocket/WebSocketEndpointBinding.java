package com.fastjava.websocket;

import com.fastjava.websocket.annotation.PathParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Objects;

public final class WebSocketEndpointBinding {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEndpointBinding.class);

    private final WebSocketEndpointMetadata metadata;
    private final Object endpointInstance;
    private final WebSocketSession session;
    private final Map<String, String> pathParams;

    public WebSocketEndpointBinding(WebSocketEndpointMetadata metadata, WebSocketSession session) {
        this(metadata, session, Map.of());
    }

    public WebSocketEndpointBinding(WebSocketEndpointMetadata metadata, WebSocketSession session,
            Map<String, String> pathParams) {
        this.metadata = metadata;
        this.session = session;
        this.pathParams = pathParams == null ? Map.of() : pathParams;
        try {
            this.endpointInstance = metadata.constructor().newInstance();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to instantiate WebSocket endpoint: "
                    + metadata.endpointClass().getName(), exception);
        }
    }

    public void onOpen() {
        invoke(metadata.onOpen(), null, null);
    }

    public void onText(String message) {
        if (metadata.onMessage() == null) {
            return;
        }
        Class<?> payloadType = metadata.onMessagePayloadType();
        if (payloadType != String.class) {
            logger.debug("Ignoring text payload for binary-only endpoint {}", metadata.endpointClass().getName());
            return;
        }
        invoke(metadata.onMessage(), message, null);
    }

    public void onBinary(byte[] payload) {
        if (metadata.onMessage() == null) {
            return;
        }
        Class<?> payloadType = metadata.onMessagePayloadType();
        if (payloadType != byte[].class) {
            logger.debug("Ignoring binary payload for text-only endpoint {}", metadata.endpointClass().getName());
            return;
        }
        invoke(metadata.onMessage(), payload, null);
    }

    public void onClose() {
        invoke(metadata.onClose(), null, null);
    }

    public void onError(Throwable throwable) {
        invoke(metadata.onError(), null, throwable);
    }

    private void invoke(Method method, Object payload, Throwable throwable) {
        if (method == null) {
            return;
        }

        Object[] args = resolveInvocationArguments(method, payload, throwable);
        try {
            method.invoke(endpointInstance, args);
        } catch (IllegalAccessException illegalAccessException) {
            throw new IllegalStateException("Endpoint method is inaccessible: " + method.getName(),
                    illegalAccessException);
        } catch (InvocationTargetException invocationTargetException) {
            Throwable cause = invocationTargetException.getCause() == null
                    ? invocationTargetException
                    : invocationTargetException.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Endpoint invocation failed: " + method.getName(), cause);
        }
    }

    private Object[] resolveInvocationArguments(Method method, Object payload, Throwable throwable) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        boolean payloadAssigned = false;
        boolean throwableAssigned = false;

        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            Class<?> type = parameter.getType();

            if (type == WebSocketSession.class) {
                args[index] = session;
                continue;
            }

            if (!payloadAssigned && payload != null
                    && ((payload instanceof String && type == String.class)
                            || (payload instanceof byte[] && type == byte[].class))) {
                args[index] = payload;
                payloadAssigned = true;
                continue;
            }

            if (!throwableAssigned && throwable != null && Throwable.class.isAssignableFrom(type)) {
                args[index] = throwable;
                throwableAssigned = true;
                continue;
            }

            PathParam pathParam = parameter.getAnnotation(PathParam.class);
            if (pathParam != null && type == String.class) {
                args[index] = pathParams.get(pathParam.value());
                continue;
            }

            throw new IllegalStateException("Unsupported endpoint parameter in method " + method.getName() + ": "
                    + type.getName());
        }

        return args;
    }
}
