package io.github.p3chys.cooperativearchi.ui;

import java.beans.PropertyChangeListener;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/** Optional automatic syncs: after each save of a shared model, and/or every N minutes. */
final class AutoSync {

    private static PropertyChangeListener saveListener;
    private static Job timer;

    private AutoSync() {
    }

    static synchronized void start() {
        if (saveListener == null) {
            saveListener = e -> {
                if (!IEditorModelManager.PROPERTY_MODEL_SAVED.equals(e.getPropertyName())
                        || !Prefs.flag(Prefs.SYNC_ON_SAVE, false)) {
                    return;
                }
                Object m = e.getNewValue() instanceof IArchimateModel ? e.getNewValue() : e.getOldValue();
                if (m instanceof IArchimateModel model && ModelSupport.isShared(model)) {
                    Display.getDefault().asyncExec(() -> SyncRunner.sync(model, SyncRunner.Mode.AUTO, null));
                }
            };
            IEditorModelManager.INSTANCE.addPropertyChangeListener(saveListener);
        }
        reschedule();
    }

    static synchronized void reschedule() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
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
        if (saveListener != null) {
            IEditorModelManager.INSTANCE.removePropertyChangeListener(saveListener);
            saveListener = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }
}
