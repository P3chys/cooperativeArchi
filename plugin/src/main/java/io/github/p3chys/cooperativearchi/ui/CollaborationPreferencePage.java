package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Preferences > Collaboration. */
public class CollaborationPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Label account;
    private Text name;
    private Text email;
    private Text folder;
    private Button syncOnSave;
    private Spinner minutes;
    private Button askMessage;
    private Button live;
    private Spinner idle;
    private Spinner poll;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Settings for sharing models with your colleagues through GitHub.");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, false));

        new Label(c, SWT.NONE).setText("GitHub account:");
        account = new Label(c, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(account);
        Button token = new Button(c, SWT.PUSH);
        token.setText("Access token...");
        token.addListener(SWT.Selection, e -> {
            TokenDialog.ask(getShell());
            load();
        });

        new Label(c, SWT.NONE).setText("Your name in history:");
        name = new Text(c, SWT.BORDER);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(name);
        new Label(c, SWT.NONE).setText("Your e-mail in history:");
        email = new Text(c, SWT.BORDER);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(email);

        new Label(c, SWT.NONE).setText("Folder for shared models:");
        folder = new Text(c, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(folder);
        Button browse = new Button(c, SWT.PUSH);
        browse.setText("Browse...");
        browse.addListener(SWT.Selection, e -> {
            DirectoryDialog d = new DirectoryDialog(getShell());
            d.setFilterPath(folder.getText());
            String dir = d.open();
            if (dir != null) {
                folder.setText(dir);
            }
        });

        live = new Button(c, SWT.CHECK);
        live.setText("Live collaboration: save automatically and exchange changes with colleagues every few seconds");
        GridDataFactory.fillDefaults().span(3, 1).indent(0, 10).applyTo(live);
        new Label(c, SWT.NONE).setText("Save after a pause in editing of (seconds):");
        idle = new Spinner(c, SWT.BORDER);
        idle.setValues(Prefs.DEFAULT_LIVE_IDLE_TENTHS, 5, 100, 1, 5, 10);
        new Label(c, SWT.NONE);
        new Label(c, SWT.NONE).setText("Look for colleagues' changes every (seconds):");
        poll = new Spinner(c, SWT.BORDER);
        poll.setValues(Prefs.DEFAULT_LIVE_POLL_SECONDS, 2, 120, 0, 1, 10);
        new Label(c, SWT.NONE);
        live.addListener(SWT.Selection, e -> enableLive());

        syncOnSave = new Button(c, SWT.CHECK);
        syncOnSave.setText("Sync automatically every time a shared model is saved");
        GridDataFactory.fillDefaults().span(3, 1).indent(0, 10).applyTo(syncOnSave);

        new Label(c, SWT.NONE).setText("Also sync every (minutes, 0 = off):");
        minutes = new Spinner(c, SWT.BORDER);
        minutes.setValues(0, 0, 240, 0, 1, 10);
        Label hint = new Label(c, SWT.WRAP);
        hint.setText("only models without unsaved changes");

        askMessage = new Button(c, SWT.CHECK);
        askMessage.setText("Ask for a short description of my changes when I push");
        GridDataFactory.fillDefaults().span(3, 1).applyTo(askMessage);

        load();
        return c;
    }

    private void load() {
        String login = Prefs.get(Prefs.LOGIN, "");
        account.setText(Prefs.token() == null ? "not connected" : login.isEmpty() ? "token stored" : login);
        account.getParent().layout();
        name.setText(Prefs.get(Prefs.NAME, ""));
        email.setText(Prefs.get(Prefs.EMAIL, ""));
        folder.setText(Prefs.folder().toString());
        syncOnSave.setSelection(Prefs.flag(Prefs.SYNC_ON_SAVE, false));
        minutes.setSelection(Prefs.number(Prefs.SYNC_MINUTES, 0));
        askMessage.setSelection(Prefs.flag(Prefs.ASK_MESSAGE, false));
        live.setSelection(Prefs.flag(Prefs.LIVE, false));
        idle.setSelection(Prefs.number(Prefs.LIVE_IDLE_TENTHS, Prefs.DEFAULT_LIVE_IDLE_TENTHS));
        poll.setSelection(Prefs.number(Prefs.LIVE_POLL_SECONDS, Prefs.DEFAULT_LIVE_POLL_SECONDS));
        enableLive();
    }

    private void enableLive() {
        idle.setEnabled(live.getSelection());
        poll.setEnabled(live.getSelection());
        syncOnSave.setEnabled(!live.getSelection()); // live mode always syncs on save
    }

    @Override
    protected void performDefaults() {
        syncOnSave.setSelection(false);
        minutes.setSelection(0);
        askMessage.setSelection(false);
        live.setSelection(false);
        idle.setSelection(Prefs.DEFAULT_LIVE_IDLE_TENTHS);
        poll.setSelection(Prefs.DEFAULT_LIVE_POLL_SECONDS);
        enableLive();
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        Prefs.put(Prefs.NAME, name.getText().trim());
        Prefs.put(Prefs.EMAIL, email.getText().trim());
        Prefs.put(Prefs.FOLDER, folder.getText().trim());
        Prefs.put(Prefs.SYNC_ON_SAVE, syncOnSave.getSelection());
        Prefs.put(Prefs.SYNC_MINUTES, minutes.getSelection());
        Prefs.put(Prefs.ASK_MESSAGE, askMessage.getSelection());
        Prefs.put(Prefs.LIVE, live.getSelection());
        Prefs.put(Prefs.LIVE_IDLE_TENTHS, idle.getSelection());
        Prefs.put(Prefs.LIVE_POLL_SECONDS, poll.getSelection());
        AutoSync.reschedule();
        return true;
    }
}
