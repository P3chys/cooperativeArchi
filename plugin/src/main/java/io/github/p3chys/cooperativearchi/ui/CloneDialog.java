package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.git.SyncOptions;

/** Downloads a shared model from GitHub and opens it. */
final class CloneDialog extends TitleAreaDialog {

    private Text url;
    private Text folder;
    String repoUrl;
    String folderName;

    private CloneDialog(Shell parent) {
        super(parent);
    }

    static void run() {
        Shell shell = ModelSupport.shell();
        CloneDialog d = new CloneDialog(shell);
        if (d.open() != OK) {
            return;
        }
        CredentialsProvider creds;
        try {
            creds = SyncRunner.credentials(d.repoUrl, true);
        } catch (IllegalStateException e) {
            return;
        }
        Path workspace = Prefs.folder().resolve(d.folderName);
        try {
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Downloading " + d.repoUrl, IProgressMonitor.UNKNOWN);
                SyncOptions o = SyncOptions.of(creds, Prefs.author(), null).withMonitor(new JGitMonitor(monitor));
                try (SharedModel sm = SharedModel.cloneFrom(d.repoUrl, workspace, o)) {
                    // downloaded
                } catch (IOException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            MessageDialog.openError(shell, "Open Shared Model", String.valueOf(e.getCause().getMessage()));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ModelSupport.open(SharedModel.modelFileIn(workspace));
        SyncRunner.fireChanged();
        ModelSupport.notify("Shared model opened", "Edit, save, then press Sync to exchange changes with your colleagues.");
    }

    /** Accepts "owner/repo", a GitHub page URL or a clone URL. */
    static String normalizeUrl(String input) {
        String s = input.trim();
        if (s.matches("[\\w.-]+/[\\w.-]+")) {
            return "https://github.com/" + s + ".git";
        }
        if (s.startsWith("https://github.com/") && !s.endsWith(".git")) {
            s = s.replaceAll("/+$", "");
            String[] parts = s.substring("https://github.com/".length()).split("/");
            if (parts.length >= 2) {
                return "https://github.com/" + parts[0] + "/" + parts[1] + ".git";
            }
        }
        return s;
    }

    static String repoName(String url) {
        String s = url.replaceAll("/+$", "").replaceAll("\\.git$", "");
        return s.substring(s.lastIndexOf('/') + 1);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Open Shared Model");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Open a shared model from GitHub");
        setMessage("Paste the repository address a colleague gave you, e.g. owner/repo or its GitHub URL.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).indent(10, 10).applyTo(c);
        c.setLayout(new GridLayout(2, false));

        new Label(c, SWT.NONE).setText("Repository:");
        url = new Text(c, SWT.BORDER);
        url.setMessage("owner/repo or https://github.com/owner/repo");
        GridDataFactory.fillDefaults().grab(true, false).hint(380, SWT.DEFAULT).applyTo(url);

        new Label(c, SWT.NONE).setText("Local folder name:");
        folder = new Text(c, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(folder);
        url.addModifyListener(e -> folder.setText(repoName(normalizeUrl(url.getText()))));

        Label where = new Label(c, SWT.WRAP);
        where.setText("Saved under " + Prefs.folder());
        GridDataFactory.fillDefaults().span(2, 1).applyTo(where);
        return area;
    }

    @Override
    protected void okPressed() {
        String u = normalizeUrl(url.getText());
        String f = folder.getText().trim();
        if (u.isEmpty()) {
            setErrorMessage("Enter the repository.");
            return;
        }
        if (!f.matches("[\\w .-]+")) {
            setErrorMessage("Invalid folder name.");
            return;
        }
        if (Files.exists(Prefs.folder().resolve(f))) {
            setErrorMessage("'" + f + "' already exists in " + Prefs.folder() + ". Open its .archimate file instead, or pick another name.");
            return;
        }
        repoUrl = u;
        folderName = f;
        super.okPressed();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Download and open", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
