package com.paicli.cli;

final class CliCommandParser {

    enum CommandType {
        NONE,
        UNKNOWN_COMMAND,
        CANCEL,
        EXIT,
        CLEAR,
        SWITCH_MODEL,
        SWITCH_PLAN,
        SWITCH_TEAM,
        SWITCH_HITL,
        MEMORY_STATUS,
        MEMORY_CLEAR,
        MEMORY_SAVE,
        INDEX_CODE,
        SEARCH_CODE,
        GRAPH_QUERY,
        CONTEXT_STATUS,
        POLICY_STATUS,
        AUDIT_TAIL,
        MCP_LIST,
        MCP_RESTART,
        MCP_LOGS,
        MCP_DISABLE,
        MCP_ENABLE,
        MCP_RESOURCES,
        MCP_PROMPTS,
        BROWSER,
        SKILL_LIST,
        SKILL_SHOW,
        SKILL_ON,
        SKILL_OFF,
        SKILL_RELOAD
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("/cancel") || trimmed.equalsIgnoreCase("cancel")) {
            return new ParsedCommand(CommandType.CANCEL, null);
        }

        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, null);
        }

        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/team")) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, null);
        }

        if (trimmed.regionMatches(true, 0, "/team ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/hitl on")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "on");
        }

        if (trimmed.equalsIgnoreCase("/hitl off")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "off");
        }

        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, null);
        }

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/memory clear") || trimmed.equalsIgnoreCase("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/save")) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, null);
        }

        if (trimmed.regionMatches(true, 0, "/save ", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/index")) {
            return new ParsedCommand(CommandType.INDEX_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/index ", 0, 7)) {
            return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/search")) {
            return new ParsedCommand(CommandType.SEARCH_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/search ", 0, 8)) {
            return new ParsedCommand(CommandType.SEARCH_CODE, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/graph")) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, null);
        }

        if (trimmed.regionMatches(true, 0, "/graph ", 0, 7)) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/context") || trimmed.equalsIgnoreCase("/ctx")) {
            return new ParsedCommand(CommandType.CONTEXT_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/policy")) {
            return new ParsedCommand(CommandType.POLICY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/audit")) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, null);
        }

        if (trimmed.regionMatches(true, 0, "/audit ", 0, 7)) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/browser")) {
            return new ParsedCommand(CommandType.BROWSER, "status");
        }

        if (trimmed.regionMatches(true, 0, "/browser ", 0, 9)) {
            return new ParsedCommand(CommandType.BROWSER, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/skill") || trimmed.equalsIgnoreCase("/skill list")) {
            return new ParsedCommand(CommandType.SKILL_LIST, null);
        }

        if (trimmed.equalsIgnoreCase("/skill reload")) {
            return new ParsedCommand(CommandType.SKILL_RELOAD, null);
        }

        if (trimmed.regionMatches(true, 0, "/skill show ", 0, 12)) {
            return new ParsedCommand(CommandType.SKILL_SHOW, trimmed.substring(12).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill on ", 0, 10)) {
            return new ParsedCommand(CommandType.SKILL_ON, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill off ", 0, 11)) {
            return new ParsedCommand(CommandType.SKILL_OFF, trimmed.substring(11).trim());
        }

        if (trimmed.equalsIgnoreCase("/mcp")) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }

        if (trimmed.regionMatches(true, 0, "/mcp resources ", 0, 15)) {
            return new ParsedCommand(CommandType.MCP_RESOURCES, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp prompts ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_PROMPTS, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp restart ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_RESTART, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp logs ", 0, 10)) {
            return new ParsedCommand(CommandType.MCP_LOGS, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp disable ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_DISABLE, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp enable ", 0, 12)) {
            return new ParsedCommand(CommandType.MCP_ENABLE, trimmed.substring(12).trim());
        }

        if (trimmed.startsWith("/")) {
            return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
        }

        return ParsedCommand.none();
    }
}
