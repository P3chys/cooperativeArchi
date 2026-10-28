package io.github.p3chys.cooperativearchi.git;

import java.time.Instant;

import org.eclipse.jgit.lib.ObjectId;

/** One version of a shared model. */
public record HistoryEntry(ObjectId id, String message, String author, String email, Instant time, boolean merge,
        boolean uploaded, boolean current) {

    public String shortId() {
        return id.abbreviate(7).name();
    }
}
