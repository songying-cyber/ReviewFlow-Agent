package com.paicli.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TuiBootstrapTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("paicli.tui");
        System.clearProperty("paicli.renderer");
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

    @Test
    void shouldUseTuiWhenRendererModeIsLanterna() {
        System.setProperty("paicli.renderer", "lanterna");
        Terminal terminal = mock(Terminal.class);
        when(terminal.getSize()).thenReturn(new Size(120, 40));

        assertTrue(TuiBootstrap.shouldUseTui(terminal));
    }

    @Test
    void rendererModeOverridesLegacyTuiFlag() {
        System.setProperty("paicli.renderer", "inline");
        System.setProperty("paicli.tui", "true");

        assertFalse(TuiBootstrap.shouldUseTui(null));
    }
}
