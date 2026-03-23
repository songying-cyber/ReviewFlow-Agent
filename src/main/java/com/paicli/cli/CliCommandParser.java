package com.paicli.cli;

final class CliCommandParser {

    enum CommandType {
        NONE,
        UNKNOWN_COMMAND,
        EXIT,
        CLEAR,
        SWITCH_PLAN,
        SWITCH_TEAM,
        MEMORY_STATUS,
        MEMORY_SAVE,
        INDEX_CODE,
        SEARCH_CODE,
        GRAPH_QUERY
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

        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
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

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
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

        if (trimmed.startsWith("/")) {
            return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
        }

        return ParsedCommand.none();
    }
}
