package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCapabilitiesTest {

    private String savedSysProp;

    @BeforeEach
    void save() {
        savedSysProp = System.getProperty("paicli.no.statusbar");
    }

    @AfterEach
    void restore() {
        if (savedSysProp == null) {
            System.clearProperty("paicli.no.statusbar");
        } else {
            System.setProperty("paicli.no.statusbar", savedSysProp);
        }
    }

    @Test
    void nullTerminalIsNotAnsiCapable() {
        assertFalse(TerminalCapabilities.supportsAnsi(null));
    }

    @Test
    void dumbTerminalIsNotAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        assertFalse(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void xtermTerminalIsAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        assertTrue(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void scrollRegionRequiresMinimumSize() {
        Terminal small = Mockito.mock(Terminal.class);
        Mockito.when(small.getType()).thenReturn("xterm-256color");
        Mockito.when(small.getSize()).thenReturn(new Size(40, 4));
        assertFalse(TerminalCapabilities.supportsScrollRegion(small));
    }

    @Test
    void scrollRegionTrueOnNormalTerminal() {
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertTrue(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void noStatusbarPropertyDisablesScrollRegion() {
        System.setProperty("paicli.no.statusbar", "true");
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertFalse(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void safeSizeFallbackOnNullSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(null);
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }

    @Test
    void safeSizeFallbackOnZeroSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(0, 0));
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }
}
