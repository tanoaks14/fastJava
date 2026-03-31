package com.fastjava.server.session;

import com.fastjava.servlet.HttpSession;

/**
 * Session manager abstraction for request/session lifecycle coordination.
 */
public interface SessionManager {

    SessionConfig config();

    HttpSession findSession(String sessionId);

    HttpSession createSession();

    void invalidate(String sessionId);

    void markSessionEstablished(String sessionId);

    boolean isSessionActive(String sessionId);

    void runMaintenance();
}
