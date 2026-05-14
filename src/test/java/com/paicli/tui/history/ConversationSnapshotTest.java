package com.paicli.tui.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSnapshotTest {

    @Test
    void appendsUserAndAssistantMessages() {
        ConversationSnapshot snapshot = new ConversationSnapshot("test-session");
        assertEquals("test-session", snapshot.getSessionId());
        assertTrue(snapshot.getMessages().isEmpty());

        snapshot.appendUser("Hello");
        snapshot.appendAssistant("Hi there!");

        assertEquals(2, snapshot.getMessages().size());
        assertEquals("user", snapshot.getMessages().get(0).role());
        assertEquals("Hello", snapshot.getMessages().get(0).content());
        assertEquals("assistant", snapshot.getMessages().get(1).role());
        assertEquals("Hi there!", snapshot.getMessages().get(1).content());
    }

    @Test
    void generatesUniqueSessionIds() {
        String id1 = ConversationSnapshot.generateSessionId();
        String id2 = ConversationSnapshot.generateSessionId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("session_"));
    }
}
