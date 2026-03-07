package com.paicli.cli;

final class CliCommandParser {

    enum CommandType {
        NONE,
        EXIT,
        CLEAR,
        SWITCH_PLAN,
        MEMORY_STATUS,
        MEMORY_SAVE
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

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.regionMatches(true, 0, "/save ", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        return ParsedCommand.none();
    }
}
