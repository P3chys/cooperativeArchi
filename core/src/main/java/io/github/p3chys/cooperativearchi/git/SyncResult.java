package io.github.p3chys.cooperativearchi.git;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.ObjectId;

import io.github.p3chys.cooperativearchi.merge.ModelDiff;

/** Outcome of {@link SharedModel#sync}, {@link SharedModel#pull} and {@link SharedModel#push}. */
public final class SyncResult {

    /** Local edits were committed (summary of what changed). */
    String committed;
    /** Changes from colleagues were merged in (summary of what changed). */
    String received;
    boolean pushed;
    /** Network problem: work was committed locally and will be pushed on the next sync. */
    String offlineReason;
    int conflictsResolved;
    final List<String> notes = new ArrayList<>();
    /** Pull: what changes in the local model; push: what changed on GitHub. Null for sync or when up to date. */
    ModelDiff diff;

    ObjectId newHead;
    /** Model content to load into Archi, or null when the file on disk is already up to date. */
    byte[] modelToLoad;
    byte[] fileHashAtRead;

    public String committed() {
        return committed;
    }

    public String received() {
        return received;
    }

    public boolean pushed() {
        return pushed;
    }

    public String offlineReason() {
        return offlineReason;
    }

    public int conflictsResolved() {
        return conflictsResolved;
    }

    public List<String> notes() {
        return notes;
    }

    public ModelDiff diff() {
        return diff;
    }

    public ObjectId newHead() {
        return newHead;
    }

    /** True if Archi must reload the model file to show merged changes. */
    public boolean needsReload() {
        return modelToLoad != null;
    }

    public boolean nothingHappened() {
        return committed == null && received == null && !pushed && offlineReason == null;
    }

    /** One line report for the status bar / CLI. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (committed != null) {
            parts.add("Sent: " + committed);
        }
        if (received != null) {
            parts.add("Received: " + received);
        }
        if (conflictsResolved > 0) {
            parts.add(conflictsResolved + " conflict(s) resolved");
        }
        if (offlineReason != null) {
            parts.add("Not uploaded (" + offlineReason + ") - saved locally, will upload on next sync");
        }
        if (parts.isEmpty()) {
            return "Already up to date";
        }
        return String.join(". ", parts);
    }
}
