package com.paicli.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TuiBootstrapTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("paicli.tui");
    }

    @Test
    void shouldKeepCliByDefault() {
        System.clearProperty("paicli.tui");

        assertFalse(TuiBootstrap.shouldUseTui(null));
    }

    @Test
    void shouldDegradeWhenTuiRequestedButTerminalUnavailable() {
        System.setProperty("paicli.tui", "true");
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));

            assertFalse(TuiBootstrap.shouldUseTui(null));
        } finally {
            System.setOut(originalOut);
        }
    }
}
