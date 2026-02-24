package com.paicli.cli;

final class CliCommandParser {

    enum CommandType {
        NONE,
        EXIT,
        SELECT_MODE,
        CLEAR,
        SWITCH_PLAN
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

        if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("mode")) {
            return new ParsedCommand(CommandType.SELECT_MODE, null);
        }

        if (trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, trimmed.substring(6).trim());
        }

        return ParsedCommand.none();
    }
}
