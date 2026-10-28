package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;

/** Shows JGit transfer progress in an Eclipse progress monitor. */
final class JGitMonitor implements ProgressMonitor {

    private final IProgressMonitor monitor;

    JGitMonitor(IProgressMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void start(int totalTasks) {
    }

    @Override
    public void beginTask(String title, int totalWork) {
        monitor.subTask(title);
    }

    @Override
    public void update(int completed) {
    }

    @Override
    public void endTask() {
    }

    @Override
    public boolean isCancelled() {
        return monitor.isCanceled();
    }

    @Override
    public void showDuration(boolean enabled) {
    }
}
