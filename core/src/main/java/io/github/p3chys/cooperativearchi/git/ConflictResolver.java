package io.github.p3chys.cooperativearchi.git;

import io.github.p3chys.cooperativearchi.merge.ModelMerger.MergeSession;

/** Lets the user pick a side for each conflict. Called on the sync thread. */
@FunctionalInterface
public interface ConflictResolver {

    /**
     * @param theirsLabel who/what "theirs" is, e.g. "origin/main" or "branch 'draft'"
     * @return false to cancel the sync (nothing is changed)
     */
    boolean resolve(MergeSession session, String theirsLabel);

    ConflictResolver FAIL = (session, label) -> {
        throw new IllegalStateException(session.conflicts().size() + " conflict(s) with " + label + ": " + session.conflicts());
    };
}
