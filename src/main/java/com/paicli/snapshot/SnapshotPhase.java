package com.paicli.snapshot;

public enum SnapshotPhase {
    PRE_TURN("pre-turn"),
    POST_TURN("post-turn"),
    PRE_RESTORE("pre-restore");

    private final String label;

    SnapshotPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
