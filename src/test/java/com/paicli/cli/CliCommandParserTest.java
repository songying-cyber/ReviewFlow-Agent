package com.paicli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliCommandParserTest {

    @Test
    void parsesPlanSlashCommandWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/plan");

        assertEquals(CliCommandParser.CommandType.SWITCH_PLAN, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesPlanSlashCommandWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/plan 创建一个 demo 项目");

        assertEquals(CliCommandParser.CommandType.SWITCH_PLAN, command.type());
        assertEquals("创建一个 demo 项目", command.payload());
    }

    @Test
    void keepsNormalInputAsNone() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("帮我读取 pom.xml");

        assertEquals(CliCommandParser.CommandType.NONE, command.type());
        assertNull(command.payload());
    }
}
