package com.paicli.github;

public record GitHubDiffPosition(
        String path,
        int line,
        String side,
        int oldLine,
        int newLine,
        String type
) {
    public boolean isRightSide() {
        return "RIGHT".equalsIgnoreCase(side);
    }

    public boolean isLeftSide() {
        return "LEFT".equalsIgnoreCase(side);
    }
}
