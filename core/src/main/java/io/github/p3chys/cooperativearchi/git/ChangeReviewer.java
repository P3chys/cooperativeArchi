package io.github.p3chys.cooperativearchi.git;

import io.github.p3chys.cooperativearchi.merge.ModelDiff;

/** Shown what a pull or push is about to change, before anything is applied or uploaded. */
@FunctionalInterface
public interface ChangeReviewer {

    enum Direction {
        /** The diff turns the local model into the merged model that will be loaded. */
        PULL,
        /** The diff turns the model on GitHub into the version that will be uploaded. */
        PUSH
    }

    /** Accepts everything (automatic sync, scripts). */
    ChangeReviewer ACCEPT = (diff, direction) -> true;

    /** @return true to go ahead, false to cancel (nothing is applied or uploaded) */
    boolean review(ModelDiff diff, Direction direction);
}
