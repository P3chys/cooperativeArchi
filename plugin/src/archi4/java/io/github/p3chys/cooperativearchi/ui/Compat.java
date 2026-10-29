package io.github.p3chys.cooperativearchi.ui;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.notifications.AbstractNotificationPopup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.compatibility.CompatibilityHandlerException;
import com.archimatetool.editor.model.compatibility.IncompatibleModelException;
import com.archimatetool.editor.model.compatibility.ModelCompatibility;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.ModelVersion;
import com.archimatetool.model.util.ArchimateResourceFactory;

/**
 * Archi / Eclipse calls that differ between Archi lines: the Archi 4.9 versions (Eclipse 4.21), for what Archi 5
 * or later Eclipse added (src/archi5 has those).
 */
final class Compat {

    /**
     * Highest model version to write shared models with. Archi 4.9 asks "open anyway?" for every model saved by
     * Archi 5 (model version 5.0.0), whose content it reads fine.
     */
    static final String MAX_MODEL_VERSION = ModelVersion.VERSION;

    private Compat() {
    }

    /** Loads a model file without opening it in Archi (nothing is shown or registered); Archi 5's load(File). */
    static IArchimateModel load(File file) throws IOException {
        Resource resource = ArchimateResourceFactory.createNewResource(IArchiveManager.FACTORY.isArchiveFile(file)
                ? IArchiveManager.FACTORY.createArchiveModelURI(file) : URI.createFileURI(file.getAbsolutePath()));
        ModelCompatibility compatibility = new ModelCompatibility(resource);
        try {
            resource.load(null);
        } catch (IOException e) {
            if (resource.getErrors().isEmpty()) {
                throw e;
            }
            try {
                compatibility.checkErrors();
            } catch (IncompatibleModelException ex) {
                throw new IOException("Cannot load " + file + "\n" + ex.getMessage(), ex);
            }
        }
        IArchimateModel model = (IArchimateModel) resource.getContents().get(0);
        try {
            compatibility.fixCompatibility();
        } catch (CompatibilityHandlerException e) {
            // as Archi: the model loads as it is
        }
        model.setFile(file);
        model.setDefaults();
        model.setAdapter(CommandStack.class, new CommandStack());
        IArchiveManager images = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, images);
        images.loadImages();
        return model;
    }

    static void notify(Display d, String title, String text) {
        AbstractNotificationPopup popup = new AbstractNotificationPopup(d) {
            @Override
            protected String getPopupShellTitle() {
                return title;
            }

            @Override
            protected void createContentArea(Composite parent) {
                Label label = new Label(parent, SWT.WRAP);
                label.setText(text);
                GridDataFactory.fillDefaults().grab(true, false).hint(300, SWT.DEFAULT).applyTo(label);
            }
        };
        popup.setDelayClose(6000);
        popup.open();
    }

    static ILog log() {
        return Platform.getLog(Activator.class);
    }
}
