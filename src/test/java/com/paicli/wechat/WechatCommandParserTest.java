package com.paicli.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatCommandParserTest {
    @Test
    void parsesBypassControlCommands() {
        WechatCommandParser.Command stop = WechatCommandParser.parse("/stop");
        assertEquals(WechatCommandParser.Type.STOP, stop.type());
        assertTrue(stop.bypassQueue());

        WechatCommandParser.Command status = WechatCommandParser.parse("/status");
        assertEquals(WechatCommandParser.Type.STATUS, status.type());
        assertTrue(status.bypassQueue());
    }

    @Test
    void parsesQueuedBusinessCommands() {
        WechatCommandParser.Command compact = WechatCommandParser.parse("/compact");
        assertEquals(WechatCommandParser.Type.COMPACT, compact.type());
        assertFalse(compact.bypassQueue());
    }

    @Test
    void rejectsUnknownSlashCommands() {
        WechatCommandParser.Command command = WechatCommandParser.parse("/mcp restart chrome-devtools");
        assertEquals(WechatCommandParser.Type.UNKNOWN, command.type());
        assertTrue(command.bypassQueue());
    }
}
