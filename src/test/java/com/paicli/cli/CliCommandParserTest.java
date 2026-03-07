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
    void parsesClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/clear");

        assertEquals(CliCommandParser.CommandType.CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesExitSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/exit");

        assertEquals(CliCommandParser.CommandType.EXIT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemorySlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory");

        assertEquals(CliCommandParser.CommandType.MEMORY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesSaveSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/save 记住这个事实");

        assertEquals(CliCommandParser.CommandType.MEMORY_SAVE, command.type());
        assertEquals("记住这个事实", command.payload());
    }

    @Test
    void keepsNormalInputAsNone() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("帮我读取 pom.xml");

        assertEquals(CliCommandParser.CommandType.NONE, command.type());
        assertNull(command.payload());
    }
}
