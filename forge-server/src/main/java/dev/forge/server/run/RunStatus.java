package dev.forge.server.run;

public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }
}
