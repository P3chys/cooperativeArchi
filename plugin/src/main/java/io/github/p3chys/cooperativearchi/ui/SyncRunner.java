package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

import io.github.p3chys.cooperativearchi.git.ConflictResolver;
import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.git.SyncCancelledException;
import io.github.p3chys.cooperativearchi.git.SyncOptions;
import io.github.p3chys.cooperativearchi.git.SyncResult;
import io.github.p3chys.cooperativearchi.github.GitHubClient;

/** Runs syncs from the UI: save, sync with progress, resolve conflicts, reload, report. */
final class SyncRunner {

    enum Mode {
        /** Started by the user: progress dialog, errors in dialogs. */
        INTERACTIVE,
        /** Started automatically: background job, errors as notifications. */
        AUTO
    }

    /** Outcome: the model instance to continue with (reloaded models are new instances), or null. */
    record Outcome(IArchimateModel model, boolean ok) {
    }

    @FunctionalInterface
    interface Work {
        SyncResult run(SharedModel sm, SyncOptions options) throws IOException, SyncCancelledException;
    }

    /** Notified after every sync / branch / restore operation (e.g. to refresh the history view). */
    static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** Model files being synchronized, and those to sync again once that is done. */
    private static final Set<Path> BUSY = ConcurrentHashMap.newKeySet();
    private static final Set<Path> PENDING = ConcurrentHashMap.newKeySet();

    private SyncRunner() {
    }

    static Outcome sync(IArchimateModel model, Mode mode, String mergeFrom) {
        return run(model, mode, (sm, o) -> sm.sync(mergeFrom == null ? o : o.withMergeFrom(mergeFrom)));
    }

    static Outcome run(IArchimateModel model, Mode mode, Work work) {
        boolean interactive = mode == Mode.INTERACTIVE;
        if (model == null) {
            if (interactive) {
                MessageDialog.openInformation(ModelSupport.shell(), "Sync", "Select a model in the Models tree first.");
            }
            return new Outcome(null, false);
        }
        if (!ModelSupport.isShared(model)) {
            if (interactive && MessageDialog.openQuestion(ModelSupport.shell(), "Sync",
                    "'" + model.getName() + "' is not shared yet.\n\nShare it on GitHub now?")) {
                ShareDialog.share(model);
            }
            return new Outcome(model, false);
        }
        if (ModelSupport.isDirty(model)) {
            if (!interactive) {
                return new Outcome(model, false);
            }
            try {
                if (!IEditorModelManager.INSTANCE.saveModel(model)) {
                    return new Outcome(model, false);
                }
            } catch (IOException e) {
                MessageDialog.openError(ModelSupport.shell(), "Sync", "Could not save the model: " + e.getMessage());
                return new Outcome(model, false);
            }
        }
        Path file = model.getFile().toPath();
        SyncOptions options;
        try (SharedModel sm = SharedModel.open(file)) {
            CredentialsProvider creds = credentials(sm.remoteUrl(), interactive);
            options = SyncOptions.of(creds, Prefs.author(), resolver());
        } catch (IOException e) {
            return fail(model, mode, e.getMessage());
        } catch (IllegalStateException e) {
            return new Outcome(model, false);
        }
        if (interactive && Prefs.flag(Prefs.ASK_MESSAGE, false)) {
            InputDialog d = new InputDialog(ModelSupport.shell(), "Sync", "Describe your changes (optional):", "", null);
            if (d.open() != Window.OK) {
                return new Outcome(model, false);
            }
            options = options.withMessage(d.getValue());
        }

        if (!BUSY.add(file)) {
            // automatic: run once more when the current sync is done, so nothing saved meanwhile waits for the next edit
            if (interactive) {
                MessageDialog.openInformation(ModelSupport.shell(), "Sync", "'" + model.getName()
                        + "' is being synchronized right now. Try again in a moment.");
            } else {
                PENDING.add(file);
            }
            return new Outcome(model, false);
        }

        SyncOptions finalOptions = options;
        SyncResult[] result = new SyncResult[1];
        Exception[] error = new Exception[1];
        String title = "Synchronizing '" + model.getName() + "'";
        if (interactive) {
            try {
                new ProgressMonitorDialog(ModelSupport.shell()).run(true, false, monitor -> {
                    monitor.beginTask(title, IProgressMonitor.UNKNOWN);
                    try (SharedModel sm = SharedModel.open(file)) {
                        result[0] = work.run(sm, finalOptions.withMonitor(new JGitMonitor(monitor)));
                    } catch (Exception e) {
                        error[0] = e;
                    } finally {
                        monitor.done();
                    }
                });
            } catch (InvocationTargetException | InterruptedException e) {
                error[0] = e;
            }
            return finish(model, file, mode, result[0], error[0]);
        }
        Job job = Job.create(title, monitor -> {
            try (SharedModel sm = SharedModel.open(file)) {
                result[0] = work.run(sm, finalOptions.withMonitor(new JGitMonitor(monitor)));
            } catch (Exception e) {
                error[0] = e;
            }
            Display.getDefault().asyncExec(() -> finish(model, file, mode, result[0], error[0]));
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
        return new Outcome(model, true);
    }

    private static Outcome finish(IArchimateModel model, Path file, Mode mode, SyncResult res, Exception error) {
        Outcome outcome = report(model, file, mode, res, error);
        BUSY.remove(file);
        if (PENDING.remove(file)) {
            for (IArchimateModel m : ModelSupport.sharedModels()) {
                if (m.getFile().toPath().equals(file)) {
                    Display.getDefault().asyncExec(() -> sync(m, Mode.AUTO, null));
                }
            }
        }
        return outcome;
    }

    /** True while a sync of this model file runs (the live check skips it). */
    static boolean isBusy(Path file) {
        return BUSY.contains(file);
    }

    private static Outcome report(IArchimateModel model, Path file, Mode mode, SyncResult res, Exception error) {
        try {
            if (error instanceof SyncCancelledException) {
                ModelSupport.notify("Sync cancelled", "Nothing was changed.");
                return new Outcome(model, false);
            }
            if (error != null) {
                Throwable cause = error instanceof InvocationTargetException ite ? ite.getCause() : error;
                if (!(cause instanceof IOException)) {
                    Activator.log("Sync failed", cause);
                }
                return fail(model, mode, String.valueOf(cause.getMessage()));
            }
            IArchimateModel current = model;
            String extra = "";
            if (res.needsReload()) {
                boolean stillOpen = IEditorModelManager.INSTANCE.getModels().contains(model);
                if (stillOpen && !ModelSupport.isDirty(model)) {
                    boolean applied;
                    try (SharedModel sm = SharedModel.open(file)) {
                        applied = sm.applySync(res);
                    }
                    if (applied) {
                        current = update(model, file);
                    } else {
                        extra = "\nYou saved during the sync: Sync again to load colleagues' changes.";
                    }
                } else {
                    extra = "\nColleagues' changes will appear after you save and Sync again.";
                }
            }
            if (mode == Mode.AUTO && AutoSync.isLive()) {
                // live mode stays quiet: the status bar shows the state, the popup is for things that need attention
                LiveStatus.synced(res.received(), res.offlineReason());
                if (!res.notes().isEmpty()) {
                    ModelSupport.notify("Synced '" + current.getName() + "'", "• " + String.join("\n• ", res.notes()));
                }
            } else if (mode == Mode.INTERACTIVE || !res.nothingHappened()) {
                StringBuilder text = new StringBuilder(res.describe()).append(extra);
                res.notes().forEach(n -> text.append("\n• ").append(n));
                ModelSupport.notify("Synced '" + current.getName() + "'", text.toString());
            }
            fireChanged();
            return new Outcome(current, true);
        } catch (IOException e) {
            return fail(model, mode, e.getMessage());
        }
    }

    /**
     * Brings the open model up to the merged file on disk in place (editors, selection and undo survive). Falls back
     * to closing and reopening it if that fails or does not give exactly the merged model.
     */
    private static IArchimateModel update(IArchimateModel model, Path file) {
        IArchimateModel incoming = null;
        try {
            incoming = IEditorModelManager.INSTANCE.load(file.toFile());
            ModelReconciler.reconcile(model, incoming);
            IArchiveManager images = (IArchiveManager) model.getAdapter(IArchiveManager.class);
            IArchiveManager incomingImages = (IArchiveManager) incoming.getAdapter(IArchiveManager.class);
            for (String path : incomingImages.getLoadedImagePaths()) {
                if (images.getBytesFromEntry(path) == null) {
                    images.copyImageBytes(incomingImages, path);
                }
            }
            Set<String> diff = ModelReconciler.differences(ModelReconciler.serialize(model), ModelReconciler.serialize(incoming));
            if (diff.isEmpty()) {
                return model;
            }
            Activator.log("In-place update left differences in " + diff + ", reopening the model", null);
        } catch (IOException | RuntimeException e) {
            Activator.log("In-place update failed, reopening the model", e);
        } finally {
            if (incoming != null && incoming.getAdapter(IArchiveManager.class) instanceof IArchiveManager am) {
                am.dispose();
            }
        }
        try {
            IArchimateModel reloaded = ModelSupport.reload(model);
            return reloaded == null ? model : reloaded;
        } catch (IOException e) {
            Activator.log("Cannot reopen the model", e);
            return model;
        }
    }

    private static Outcome fail(IArchimateModel model, Mode mode, String message) {
        if (mode == Mode.INTERACTIVE) {
            MessageDialog.openError(ModelSupport.shell(), "Sync failed", message);
        } else if (AutoSync.isLive()) {
            LiveStatus.problem(message);
        } else {
            ModelSupport.notify("Automatic sync failed", message);
        }
        fireChanged();
        return new Outcome(model, false);
    }

    static void fireChanged() {
        LISTENERS.forEach(Runnable::run);
    }

    /**
     * Credentials for https://github.com remotes (never for other hosts). Interactive: asks for a token when none is stored.
     *
     * @throws IllegalStateException if a token is needed but the user did not provide one
     */
    static CredentialsProvider credentials(String remoteUrl, boolean interactive) {
        if (remoteUrl == null || !remoteUrl.contains("://") || remoteUrl.startsWith("file:")) {
            return null; // local repository: no credentials needed
        }
        if (!GitHubClient.isGitHubUrl(remoteUrl)) {
            if (interactive) {
                MessageDialog.openError(ModelSupport.shell(), "Collaboration",
                        "Only https://github.com repositories are supported. Your GitHub token is never sent to: " + remoteUrl);
            }
            throw new IllegalStateException("Unsupported remote");
        }
        String token = Prefs.token();
        if ((token == null || token.isBlank()) && interactive) {
            TokenDialog.ask(ModelSupport.shell());
            token = Prefs.token();
        }
        if (token == null || token.isBlank()) {
            if (!interactive) {
                ModelSupport.notify("Automatic sync", "No GitHub access token configured (Collaboration > GitHub Access Token).");
            }
            throw new IllegalStateException("No token");
        }
        return GitHubClient.credentials(token);
    }

    private static ConflictResolver resolver() {
        return (session, label) -> {
            boolean[] ok = new boolean[1];
            Display.getDefault().syncExec(() -> ok[0] = new ConflictDialog(ModelSupport.shell(), session, label).open() == Window.OK);
            return ok[0];
        };
    }
}
