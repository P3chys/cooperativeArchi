package io.github.p3chys.cooperativearchi.git;

import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.CredentialsProvider;

/**
 * @param credentials GitHub credentials (null for local / file remotes)
 * @param author      name and e-mail recorded in history
 * @param resolver    asked when both sides changed the same thing
 * @param message     optional commit message; null = generated summary
 * @param mergeFrom   optional branch name to merge into the current branch
 * @param reviewer    shown the changes of a pull / push before they are applied / uploaded
 */
public record SyncOptions(CredentialsProvider credentials, PersonIdent author, ConflictResolver resolver, String message,
        String mergeFrom, ProgressMonitor monitor, ChangeReviewer reviewer) {

    public SyncOptions {
        monitor = monitor == null ? NullProgressMonitor.INSTANCE : monitor;
        resolver = resolver == null ? ConflictResolver.FAIL : resolver;
        reviewer = reviewer == null ? ChangeReviewer.ACCEPT : reviewer;
    }

    public static SyncOptions of(CredentialsProvider credentials, PersonIdent author, ConflictResolver resolver) {
        return new SyncOptions(credentials, author, resolver, null, null, null, null);
    }

    public SyncOptions withMessage(String m) {
        return new SyncOptions(credentials, author, resolver, m, mergeFrom, monitor, reviewer);
    }

    public SyncOptions withMergeFrom(String branch) {
        return new SyncOptions(credentials, author, resolver, message, branch, monitor, reviewer);
    }

    public SyncOptions withMonitor(ProgressMonitor m) {
        return new SyncOptions(credentials, author, resolver, message, mergeFrom, m, reviewer);
    }

    public SyncOptions withReviewer(ChangeReviewer r) {
        return new SyncOptions(credentials, author, resolver, message, mergeFrom, monitor, r);
    }
}
