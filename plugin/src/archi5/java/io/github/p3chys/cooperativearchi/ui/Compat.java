package io.github.p3chys.cooperativearchi.ui;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/** Archi / Eclipse calls that differ between Archi lines: the Archi 5 versions (src/archi4 has Archi 4.9's). */
final class Compat {

    /** Highest model version to write shared models with, for an Archi that asks before opening newer ones. */
    static final String MAX_MODEL_VERSION = null;

    private Compat() {
    }

    /** Loads a model file without opening it in Archi (nothing is shown or registered). */
    static IArchimateModel load(File file) throws IOException {
        return IEditorModelManager.INSTANCE.load(file);
    }

    static void notify(Display d, String title, String text) {
        NotificationPopup.forDisplay(d).title(title, true).text(text).delay(6000).open();
    }

    static ILog log() {
        return ILog.of(Activator.class);
    }
}
