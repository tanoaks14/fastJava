package com.fastjava.server.session;

import com.fastjava.servlet.HttpSession;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

public class InMemorySessionManagerTest {

    @Test
    public void createSessionProducesUrlSafeIdentifier() {
        InMemorySessionManager manager = new InMemorySessionManager(SessionConfig.defaults());

        HttpSession session = manager.createSession();

        assertNotNull(session);
        assertNotNull(session.getId());
        assertTrue(session.getId().matches("^[A-Za-z0-9_-]+$"));
        assertTrue(manager.isSessionActive(session.getId()));
    }

    @Test
    public void invalidateRemovesSessionFromManager() {
        InMemorySessionManager manager = new InMemorySessionManager(SessionConfig.defaults());
        HttpSession session = manager.createSession();

        session.invalidate();

        assertFalse(manager.isSessionActive(session.getId()));
        assertNull(manager.findSession(session.getId()));
    }

    @Test
    public void expiredSessionIsRejected() {
        SessionConfig config = new SessionConfig(
                "JSESSIONID",
                "/",
                null,
                "Lax",
                true,
                false,
                1,
                32,
                1,
                32);
        MutableClock clock = new MutableClock(Instant.parse("2026-03-30T10:00:00Z"), ZoneOffset.UTC);
        InMemorySessionManager manager = new InMemorySessionManager(config, clock);

        HttpSession session = manager.createSession();
        assertNotNull(manager.findSession(session.getId()));

        clock.setInstant(Instant.parse("2026-03-30T10:00:05Z"));
        assertNull(manager.findSession(session.getId()));
    }

    @Test
    public void rejectsMalformedSessionIdentifier() {
        InMemorySessionManager manager = new InMemorySessionManager(SessionConfig.defaults());
        assertNull(manager.findSession("../bad"));
        assertFalse(manager.isSessionActive("../bad"));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant nextInstant) {
            this.instant = nextInstant;
        }
    }
}
