package com.paicli.wechat;

import java.util.Locale;

public final class WechatCommandParser {
    public enum Type {
        NONE,
        UNKNOWN,
        HELP,
        CLEAR,
        COMPACT,
        MODEL,
        CWD,
        STATUS,
        SEND,
        PAUSE,
        RESUME,
        STOP
    }

    public record Command(Type type, String payload, boolean bypassQueue) {
        public static Command none() {
            return new Command(Type.NONE, "", false);
        }
    }

    private WechatCommandParser() {
    }

    public static Command parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Command.none();
        }
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) {
            return Command.none();
        }
        int space = trimmed.indexOf(' ');
        String name = (space < 0 ? trimmed.substring(1) : trimmed.substring(1, space)).toLowerCase(Locale.ROOT);
        String payload = space < 0 ? "" : trimmed.substring(space + 1).trim();
        return switch (name) {
            case "help" -> new Command(Type.HELP, payload, true);
            case "clear" -> new Command(Type.CLEAR, payload, false);
            case "compact" -> new Command(Type.COMPACT, payload, false);
            case "model" -> new Command(Type.MODEL, payload, false);
            case "cwd" -> new Command(Type.CWD, payload, false);
            case "status" -> new Command(Type.STATUS, payload, true);
            case "send" -> new Command(Type.SEND, payload, false);
            case "pause" -> new Command(Type.PAUSE, payload, true);
            case "resume" -> new Command(Type.RESUME, payload, true);
            case "stop", "cancel" -> new Command(Type.STOP, payload, true);
            default -> new Command(Type.UNKNOWN, trimmed, true);
        };
    }
}
