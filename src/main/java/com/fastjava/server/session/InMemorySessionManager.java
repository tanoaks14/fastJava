package com.fastjava.server.session;

import com.fastjava.servlet.HttpSession;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent in-memory session manager with expiration and bounded cleanup.
 */
public final class InMemorySessionManager implements SessionManager {

    private static final int SESSION_ID_BYTES = 24;
    private static final int MAX_SESSION_ID_LENGTH = 128;

    private final SessionConfig config;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final ConcurrentHashMap<String, DefaultHttpSession> sessions;
    private final AtomicLong operationCount;

    public InMemorySessionManager(SessionConfig config) {
        this(config, Clock.systemUTC());
    }

    InMemorySessionManager(SessionConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = new SecureRandom();
        this.sessions = new ConcurrentHashMap<>();
        this.operationCount = new AtomicLong();
    }

    @Override
    public SessionConfig config() {
        return config;
    }

    @Override
    public HttpSession findSession(String sessionId) {
        maybeCleanup();
        if (!isWellFormedSessionId(sessionId)) {
            return null;
        }
        DefaultHttpSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.isValidSession() || isExpired(session)) {
            sessions.remove(sessionId, session);
            return null;
        }
        session.touch(nowMillis());
        session.markEstablished();
        return session;
    }

    @Override
    public HttpSession createSession() {
        maybeCleanup();
        long nowMillis = nowMillis();
        while (true) {
            String sessionId = generateSessionId();
            DefaultHttpSession created = new DefaultHttpSession(
                    sessionId,
                    nowMillis,
                    config.maxAttributesPerSession(),
                    () -> sessions.remove(sessionId));
            if (sessions.putIfAbsent(sessionId, created) == null) {
                return created;
            }
        }
    }

    @Override
    public void invalidate(String sessionId) {
        if (!isWellFormedSessionId(sessionId)) {
            return;
        }
        DefaultHttpSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.invalidate();
        }
    }

    @Override
    public void markSessionEstablished(String sessionId) {
        if (!isWellFormedSessionId(sessionId)) {
            return;
        }
        DefaultHttpSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.markEstablished();
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        if (!isWellFormedSessionId(sessionId)) {
            return false;
        }
        DefaultHttpSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        if (isExpired(session) || !session.isValidSession()) {
            sessions.remove(sessionId, session);
            return false;
        }
        return true;
    }

    @Override
    public void runMaintenance() {
        evictExpired(config.cleanupBatchSize());
    }

    int activeSessionCount() {
        return sessions.size();
    }

    private void maybeCleanup() {
        long current = operationCount.incrementAndGet();
        if (current % config.cleanupIntervalOperations() == 0) {
            evictExpired(config.cleanupBatchSize());
        }
    }

    private void evictExpired(int maxItems) {
        long nowMillis = nowMillis();
        if (maxItems <= 0) {
            return;
        }
        int removed = 0;
        List<Map.Entry<String, DefaultHttpSession>> snapshot = new ArrayList<>(sessions.entrySet());
        for (Map.Entry<String, DefaultHttpSession> entry : snapshot) {
            if (removed >= maxItems) {
                break;
            }
            DefaultHttpSession session = entry.getValue();
            if (session == null) {
                continue;
            }
            if (!session.isValidSession() || nowMillis - session.getLastAccessedTime() > maxInactiveMillis()) {
                if (sessions.remove(entry.getKey(), session)) {
                    session.invalidate();
                    removed++;
                }
            }
        }
    }

    private boolean isExpired(DefaultHttpSession session) {
        if (!session.isValidSession()) {
            return true;
        }
        return nowMillis() - session.getLastAccessedTime() > maxInactiveMillis();
    }

    private long maxInactiveMillis() {
        return config.maxInactiveIntervalSeconds() * 1000L;
    }

    private String generateSessionId() {
        byte[] randomBytes = new byte[SESSION_ID_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private boolean isWellFormedSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            return false;
        }
        for (int index = 0; index < sessionId.length(); index++) {
            char c = sessionId.charAt(index);
            boolean alphaNum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphaNum && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private long nowMillis() {
        return clock.millis();
    }
}
