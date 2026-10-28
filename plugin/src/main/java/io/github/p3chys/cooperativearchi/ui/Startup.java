package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.ui.IStartup;

/** Activates automatic syncing when Archi starts. */
public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        AutoSync.start();
    }
}
