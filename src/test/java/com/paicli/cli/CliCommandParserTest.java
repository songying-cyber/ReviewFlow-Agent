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
    void parsesMemoryClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory clear");

        assertEquals(CliCommandParser.CommandType.MEMORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesSaveSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/save 记住这个事实");

        assertEquals(CliCommandParser.CommandType.MEMORY_SAVE, command.type());
        assertEquals("记住这个事实", command.payload());
    }

    @Test
    void parsesSaveWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/save");

        assertEquals(CliCommandParser.CommandType.MEMORY_SAVE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesSearchWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/search");

        assertEquals(CliCommandParser.CommandType.SEARCH_CODE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesGraphWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/graph");

        assertEquals(CliCommandParser.CommandType.GRAPH_QUERY, command.type());
        assertNull(command.payload());
    }

    @Test
    void keepsNormalInputAsNone() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("帮我读取 pom.xml");

        assertEquals(CliCommandParser.CommandType.NONE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesUnknownSlashCommandAsUnknownCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/unknown");

        assertEquals(CliCommandParser.CommandType.UNKNOWN_COMMAND, command.type());
        assertEquals("/unknown", command.payload());
    }

    @Test
    void parsesTeamSlashCommandWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/team");

        assertEquals(CliCommandParser.CommandType.SWITCH_TEAM, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesTeamSlashCommandWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/team 创建并验证一个 Java 项目");

        assertEquals(CliCommandParser.CommandType.SWITCH_TEAM, command.type());
        assertEquals("创建并验证一个 Java 项目", command.payload());
    }

    @Test
    void parsesHitlOnCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl on");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertEquals("on", command.payload());
    }

    @Test
    void parsesHitlOffCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl off");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertEquals("off", command.payload());
    }

    @Test
    void parsesHitlStatusCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesPolicyStatusCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/policy");

        assertEquals(CliCommandParser.CommandType.POLICY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/audit");

        assertEquals(CliCommandParser.CommandType.AUDIT_TAIL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/audit 20");

        assertEquals(CliCommandParser.CommandType.AUDIT_TAIL, command.type());
        assertEquals("20", command.payload());
    }

    @Test
    void parsesMcpCommands() {
        assertEquals(CliCommandParser.CommandType.MCP_LIST, CliCommandParser.parse("/mcp").type());
        assertEquals(CliCommandParser.CommandType.MCP_RESTART, CliCommandParser.parse("/mcp restart filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp restart filesystem").payload());
        assertEquals(CliCommandParser.CommandType.MCP_LOGS, CliCommandParser.parse("/mcp logs filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_DISABLE, CliCommandParser.parse("/mcp disable filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_ENABLE, CliCommandParser.parse("/mcp enable filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_RESOURCES, CliCommandParser.parse("/mcp resources filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp resources filesystem").payload());
        assertEquals(CliCommandParser.CommandType.MCP_PROMPTS, CliCommandParser.parse("/mcp prompts filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp prompts filesystem").payload());
    }

    @Test
    void parsesBrowserCommands() {
        assertEquals(CliCommandParser.CommandType.BROWSER, CliCommandParser.parse("/browser").type());
        assertEquals("status", CliCommandParser.parse("/browser").payload());
        assertEquals(CliCommandParser.CommandType.BROWSER, CliCommandParser.parse("/browser status").type());
        assertEquals("status", CliCommandParser.parse("/browser status").payload());
        assertEquals("connect", CliCommandParser.parse("/browser connect").payload());
        assertEquals("connect 9333", CliCommandParser.parse("/browser connect 9333").payload());
        assertEquals("disconnect", CliCommandParser.parse("/browser disconnect").payload());
        assertEquals("tabs", CliCommandParser.parse("/browser tabs").payload());
    }

    @Test
    void parsesCancelCommand() {
        assertEquals(CliCommandParser.CommandType.CANCEL, CliCommandParser.parse("/cancel").type());
        assertEquals(CliCommandParser.CommandType.CANCEL, CliCommandParser.parse("cancel").type());
    }
}
