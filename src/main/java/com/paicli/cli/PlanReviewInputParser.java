package com.paicli.cli;

final class PlanReviewInputParser {

    enum DecisionType {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    record Decision(DecisionType type, String feedback) {
    }

    private PlanReviewInputParser() {
    }

    static Decision parse(String input) {
        if (input != null && input.equals("\u001B")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        String trimmed = input == null ? "" : input.trim();

        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("y")
                || trimmed.equalsIgnoreCase("yes")
                || trimmed.equalsIgnoreCase("run")
                || trimmed.equalsIgnoreCase("/run")) {
            return new Decision(DecisionType.EXECUTE, null);
        }

        if (trimmed.equalsIgnoreCase("cancel")
                || trimmed.equalsIgnoreCase("esc")
                || trimmed.equalsIgnoreCase("/cancel")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        return new Decision(DecisionType.SUPPLEMENT, trimmed);
    }
}
