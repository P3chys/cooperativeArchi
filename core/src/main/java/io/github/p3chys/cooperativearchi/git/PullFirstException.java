package io.github.p3chys.cooperativearchi.git;

import java.io.IOException;

/** A push found changes on GitHub that the local model does not have yet: pull them (and review them) first. */
public final class PullFirstException extends IOException {

    private static final long serialVersionUID = 1L;

    public PullFirstException() {
        super("GitHub has changes from colleagues that your model does not have yet. Pull them first, then push.");
    }
}
