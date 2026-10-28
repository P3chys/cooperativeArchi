package io.github.p3chys.cooperativearchi.git;

/** The user cancelled conflict resolution; nothing was changed. */
public class SyncCancelledException extends Exception {

    private static final long serialVersionUID = 1L;

    public SyncCancelledException() {
        super("Sync cancelled");
    }
}
