package io.github.p3chys.cooperativearchi.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

/**
 * Status bar text for live mode ("Live ● synced 4 s ago", "Live: offline"...). Live syncs report here instead of
 * popups; a problem is announced by one popup when it starts, not on every retry.
 */
public class LiveStatus extends WorkbenchWindowControlContribution {

    private static final Set<LiveStatus> INSTANCES = new CopyOnWriteArraySet<>();
    private static volatile Instant lastSync;
    private static volatile String lastReceived;
    private static volatile String problem;
    private static boolean ticking;

    private Label label;

    @Override
    protected Control createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout();
        layout.marginHeight = 0;
        layout.marginWidth = 4;
        c.setLayout(layout);
        label = new Label(c, SWT.NONE);
        label.setText("Live: synced 59 min ago   "); // reserves the width
        INSTANCES.add(this);
        c.addListener(SWT.Dispose, e -> INSTANCES.remove(this));
        show();
        tick();
        return c;
    }

    /** A live sync finished. */
    static void synced(String received, String offlineReason) {
        if (offlineReason != null) {
            problem(offlineReason);
            return;
        }
        recovered();
        lastSync = Instant.now();
        if (received != null) {
            lastReceived = received;
        }
        refresh();
    }

    /** Something keeps live mode from working; announced once until it works again. */
    static void problem(String message) {
        boolean first = problem == null;
        problem = message == null ? "unknown problem" : message;
        if (first) {
            ModelSupport.notify("Live collaboration", problem + "\nYour work is saved locally; it will be exchanged as soon as this is solved.");
        }
        refresh();
    }

    static void recovered() {
        problem = null;
        refresh();
    }

    static void refresh() {
        Display d = Display.getDefault();
        d.asyncExec(() -> INSTANCES.forEach(LiveStatus::show));
    }

    private void show() {
        if (label == null || label.isDisposed()) {
            return;
        }
        String text;
        String tip;
        if (!AutoSync.isLive()) {
            text = "";
            tip = null;
        } else if (problem != null) {
            text = "Live: not connected";
            tip = problem;
        } else if (lastSync == null) {
            text = "Live ●";
            tip = "Live collaboration is on: changes are saved and exchanged automatically.";
        } else {
            text = "Live ● synced " + ago(lastSync);
            tip = lastReceived == null ? "Nothing received yet." : "Last received: " + lastReceived;
        }
        label.setText(text);
        label.setToolTipText(tip);
    }

    /** Keeps "synced N s ago" current while a status bar shows it. */
    private static void tick() {
        if (ticking) {
            return;
        }
        ticking = true;
        Display.getDefault().timerExec(5000, new Runnable() {
            @Override
            public void run() {
                INSTANCES.forEach(LiveStatus::show);
                if (INSTANCES.isEmpty()) {
                    ticking = false;
                } else {
                    Display.getDefault().timerExec(5000, this);
                }
            }
        });
    }

    private static String ago(Instant t) {
        long s = Math.max(0, Duration.between(t, Instant.now()).toSeconds());
        if (s < 60) {
            return s + " s ago";
        }
        return s < 3600 ? s / 60 + " min ago" : "over an hour ago";
    }
}
