package io.github.p3chys.cooperativearchi.ui;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.git.SyncOptions;
import io.github.p3chys.cooperativearchi.github.GitHubClient;

/**
 * Automatic syncs: after each save of a shared model, and/or every N minutes. In live mode also: save shared models
 * after a short pause in editing, and check GitHub every few seconds for colleagues' changes (one cheap request per
 * model, a full sync only when something arrived).
 */
final class AutoSync {

    /** Backoff of the live check after failures (offline, GitHub not reachable...). */
    private static final int[] BACKOFF_SECONDS = { 30, 120 };

    private static PropertyChangeListener listener;
    private static Job timer;
    private static Job liveCheck;
    private static int failures;
    /** One pending autosave per model, rescheduled on every edit. */
    private static final Map<IArchimateModel, Runnable> autosaves = new WeakHashMap<>();

    private AutoSync() {
    }

    static boolean isLive() {
        return Prefs.flag(Prefs.LIVE, false);
    }

    static synchronized void start() {
        if (listener == null) {
            listener = e -> {
                // the command stack event comes from the model itself (values are dirty flags), the others carry it
                Object m = IEditorModelManager.COMMAND_STACK_CHANGED.equals(e.getPropertyName()) ? e.getSource()
                        : e.getNewValue() instanceof IArchimateModel ? e.getNewValue() : e.getOldValue();
                if (!(m instanceof IArchimateModel model) || !ModelSupport.isShared(model)) {
                    return;
                }
                if (IEditorModelManager.PROPERTY_MODEL_SAVED.equals(e.getPropertyName())
                        && (isLive() || Prefs.flag(Prefs.SYNC_ON_SAVE, false))) {
                    Display.getDefault().asyncExec(() -> SyncRunner.sync(model, SyncRunner.Mode.AUTO, null));
                } else if (IEditorModelManager.COMMAND_STACK_CHANGED.equals(e.getPropertyName()) && isLive()) {
                    scheduleAutosave(model);
                }
            };
            IEditorModelManager.INSTANCE.addPropertyChangeListener(listener);
        }
        reschedule();
    }

    static synchronized void reschedule() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (liveCheck != null) {
            liveCheck.cancel();
            liveCheck = null;
        }
        if (isLive()) {
            failures = 0;
            liveCheck = Job.create("cooperativeArchi live check", monitor -> {
                checkRemote();
                return Status.OK_STATUS;
            });
            liveCheck.setSystem(true);
            liveCheck.schedule();
        }
        LiveStatus.refresh();
        int minutes = Prefs.number(Prefs.SYNC_MINUTES, 0);
        if (minutes <= 0) {
            return;
        }
        long delay = minutes * 60_000L;
        timer = Job.create("cooperativeArchi periodic sync", monitor -> {
            Display.getDefault().asyncExec(() -> {
                for (IArchimateModel m : ModelSupport.sharedModels()) {
                    if (!ModelSupport.isDirty(m)) {
                        SyncRunner.sync(m, SyncRunner.Mode.AUTO, null);
                    }
                }
            });
            synchronized (AutoSync.class) {
                if (timer != null) {
                    timer.schedule(delay);
                }
            }
            return Status.OK_STATUS;
        });
        timer.setSystem(true);
        timer.schedule(delay);
    }

    static synchronized void stop() {
        if (listener != null) {
            IEditorModelManager.INSTANCE.removePropertyChangeListener(listener);
            listener = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (liveCheck != null) {
            liveCheck.cancel();
            liveCheck = null;
        }
    }

    /** Saves the model once the user has paused editing; the save then triggers the sync. UI thread. */
    private static void scheduleAutosave(IArchimateModel model) {
        Runnable save = autosaves.computeIfAbsent(model, m -> new Runnable() {
            @Override
            public void run() {
                if (!isLive() || !IEditorModelManager.INSTANCE.getModels().contains(m) || !ModelSupport.isDirty(m)) {
                    return;
                }
                if (SyncRunner.isBusy(m.getFile().toPath())) {
                    Display.getDefault().timerExec(idleMillis(), this); // a conflict dialog may be open: try later
                    return;
                }
                try {
                    IEditorModelManager.INSTANCE.saveModel(m);
                } catch (IOException e) {
                    LiveStatus.problem("Could not save the model: " + e.getMessage());
                }
            }
        });
        Display.getDefault().timerExec(idleMillis(), save); // rescheduling the same runnable restarts its timer
    }

    private static int idleMillis() {
        return Math.max(1, Prefs.number(Prefs.LIVE_IDLE_TENTHS, Prefs.DEFAULT_LIVE_IDLE_TENTHS)) * 100;
    }

    /** Background job: asks GitHub whether the branch moved and syncs the models that have something to receive. */
    private static void checkRemote() {
        List<IArchimateModel> models = new ArrayList<>();
        Display.getDefault().syncExec(() -> {
            for (IArchimateModel m : ModelSupport.sharedModels()) {
                if (!ModelSupport.isDirty(m)) { // unsaved edits: the autosave will sync soon anyway
                    models.add(m);
                }
            }
        });
        String token = Prefs.token();
        boolean ok = true;
        for (IArchimateModel m : models) {
            Path file = m.getFile().toPath();
            if (SyncRunner.isBusy(file)) {
                continue;
            }
            try (SharedModel sm = SharedModel.open(file)) {
                String url = sm.remoteUrl();
                if (url == null || !url.contains("://") || url.startsWith("file:")) {
                    if (sm.remoteMoved(SyncOptions.of(null, null, null))) {
                        Display.getDefault().asyncExec(() -> SyncRunner.sync(m, SyncRunner.Mode.AUTO, null));
                    }
                    continue;
                }
                if (!GitHubClient.isGitHubUrl(url)) {
                    continue; // never sends the token elsewhere; a manual Sync explains why
                }
                if (token == null || token.isBlank()) {
                    LiveStatus.problem("No GitHub access token configured (Collaboration > GitHub Access Token).");
                    ok = false;
                    continue;
                }
                if (sm.remoteMoved(SyncOptions.of(GitHubClient.credentials(token), null, null))) {
                    Display.getDefault().asyncExec(() -> SyncRunner.sync(m, SyncRunner.Mode.AUTO, null));
                }
            } catch (IOException e) {
                LiveStatus.problem(e.getMessage());
                ok = false;
            }
        }
        synchronized (AutoSync.class) {
            if (liveCheck == null) {
                return;
            }
            if (ok) {
                if (failures > 0) {
                    LiveStatus.recovered();
                }
                failures = 0;
            } else {
                failures++;
            }
            int seconds = failures == 0 ? Math.max(1, Prefs.number(Prefs.LIVE_POLL_SECONDS, Prefs.DEFAULT_LIVE_POLL_SECONDS))
                    : BACKOFF_SECONDS[Math.min(failures, BACKOFF_SECONDS.length) - 1];
            liveCheck.schedule(seconds * 1000L);
        }
    }
}
