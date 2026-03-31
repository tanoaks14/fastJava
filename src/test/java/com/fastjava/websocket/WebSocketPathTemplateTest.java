package com.fastjava.websocket;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebSocketPathTemplateTest {

    @Test
    public void matchesTemplateAndExtractsParams() {
        WebSocketPathTemplate template = WebSocketPathTemplate.compile("/ws/chat/{room}/{user}");

        Map<String, String> params = template.match("/ws/chat/general/alice");

        assertNotNull(params);
        assertEquals("general", params.get("room"));
        assertEquals("alice", params.get("user"));
    }

    @Test
    public void exactPatternNonTemplateMatchesWithoutParams() {
        WebSocketPathTemplate template = WebSocketPathTemplate.compile("/ws/echo");

        Map<String, String> params = template.match("/ws/echo");

        assertNotNull(params);
        assertTrue(params.isEmpty());
    }

    @Test
    public void returnsNullWhenNoTemplateMatch() {
        WebSocketPathTemplate template = WebSocketPathTemplate.compile("/ws/chat/{room}/{user}");

        assertNull(template.match("/ws/chat/general"));
        assertNull(template.match("/ws/chat/general/alice/extra"));
    }
}
