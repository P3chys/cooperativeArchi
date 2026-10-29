package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.p3chys.cooperativearchi.git.SharedModel;

public class Activator extends AbstractUIPlugin {

    public static final String ID = "io.github.p3chys.cooperativearchi";
    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        SharedModel.setMaxModelVersion(Compat.MAX_MODEL_VERSION);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        AutoSync.stop();
        instance = null;
        super.stop(context);
    }

    public static Activator get() {
        return instance;
    }

    public static ImageDescriptor icon(String name) {
        return imageDescriptorFromPlugin(ID, "icons/" + name);
    }

    public static void log(String message, Throwable t) {
        Compat.log().log(new Status(IStatus.ERROR, ID, message, t));
    }
}
