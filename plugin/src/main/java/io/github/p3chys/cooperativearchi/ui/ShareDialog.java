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
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.github.GitHubClient;

/** Turns an open model into a shared model backed by a (new) GitHub repository. */
final class ShareDialog extends TitleAreaDialog {

    private final IArchimateModel model;
    private Text name;
    private Text owner;
    private Button isPrivate;
    private Text existingUrl;
    String repoName;
    String repoOwner;
    boolean privateRepo;
    String url;

    private ShareDialog(Shell parent, IArchimateModel model) {
        super(parent);
        this.model = model;
    }

    static void share(IArchimateModel model) {
        Shell shell = ModelSupport.shell();
        if (model == null) {
            MessageDialog.openInformation(shell, "Share Model", "Select the model to share in the Models tree first.");
            return;
        }
        if (ModelSupport.isShared(model)) {
            MessageDialog.openInformation(shell, "Share Model", "'" + model.getName() + "' is already shared. Use Pull and Push.");
            return;
        }
        try {
            if ((model.getFile() == null || ModelSupport.isDirty(model)) && !IEditorModelManager.INSTANCE.saveModel(model)) {
                return;
            }
        } catch (IOException e) {
            MessageDialog.openError(shell, "Share Model", e.getMessage());
            return;
        }
        try {
            SyncRunner.credentials("https://github.com", true);
        } catch (IllegalStateException e) {
            return;
        }
        ShareDialog d = new ShareDialog(shell, model);
        if (d.open() != OK) {
            return;
        }
        Path workspace = Prefs.folder().resolve(d.repoName);
        Path source = model.getFile().toPath();
        String[] cloneUrl = { d.url };
        try {
            new ProgressMonitorDialog(shell).run(true, false, monitor -> {
                monitor.beginTask("Sharing '" + model.getName() + "'", IProgressMonitor.UNKNOWN);
                try {
                    if (cloneUrl[0] == null) {
                        monitor.subTask("Creating GitHub repository " + d.repoName);
                        cloneUrl[0] = new GitHubClient(Prefs.token()).createRepo(d.repoOwner, d.repoName, d.privateRepo,
                                "Archi model '" + model.getName() + "' shared with cooperativeArchi").cloneUrl();
                    }
                    try (SharedModel sm = SharedModel.share(source, workspace, cloneUrl[0])) {
                        // created
                    }
                } catch (IOException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            MessageDialog.openError(shell, "Share Model", String.valueOf(e.getCause().getMessage()));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            IEditorModelManager.INSTANCE.closeModel(model);
        } catch (IOException e) {
            Activator.log("Closing the original model failed", e);
        }
        IArchimateModel shared = ModelSupport.open(SharedModel.modelFileIn(workspace));
        SyncRunner.Outcome outcome = SyncRunner.sync(shared, SyncRunner.Mode.INTERACTIVE, null);
        if (outcome.ok()) {
            MessageDialog.openInformation(shell, "Share Model", "'" + model.getName() + "' is now shared.\n\n"
                    + "Colleagues open it with Collaboration > Open Shared Model from GitHub, using:\n" + cloneUrl[0]
                    + "\n\nYour shared copy is " + SharedModel.modelFileIn(workspace)
                    + "\n(the original file " + source + " is no longer used).");
        }
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Share Model");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Share '" + model.getName() + "' on GitHub");
        setMessage("A GitHub repository is created for the model. Your colleagues need access to it.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).indent(10, 10).applyTo(c);
        c.setLayout(new GridLayout(2, false));

        new Label(c, SWT.NONE).setText("Repository name:");
        name = new Text(c, SWT.BORDER);
        name.setText(suggestName(model.getName()));
        GridDataFactory.fillDefaults().grab(true, false).hint(360, SWT.DEFAULT).applyTo(name);

        new Label(c, SWT.NONE).setText("Organization (optional):");
        owner = new Text(c, SWT.BORDER);
        owner.setMessage("empty = your own account (" + Prefs.get(Prefs.LOGIN, "") + ")");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(owner);

        new Label(c, SWT.NONE);
        isPrivate = new Button(c, SWT.CHECK);
        isPrivate.setText("Private repository (recommended)");
        isPrivate.setSelection(true);

        Label sep = new Label(c, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().span(2, 1).applyTo(sep);
        new Label(c, SWT.NONE).setText("Or existing empty repository URL:");
        existingUrl = new Text(c, SWT.BORDER);
        existingUrl.setMessage("https://github.com/owner/repo.git");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(existingUrl);

        Label where = new Label(c, SWT.WRAP);
        where.setText("The shared copy will be kept in " + Prefs.folder() + " (change in Preferences > Collaboration).");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(500, SWT.DEFAULT).indent(0, 10).applyTo(where);
        return area;
    }

    static String suggestName(String modelName) {
        String s = modelName == null ? "" : modelName.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "archi-model" : s;
    }

    @Override
    protected void okPressed() {
        String n = name.getText().trim();
        String u = existingUrl.getText().trim();
        if (!n.matches("[A-Za-z0-9._-]+")) {
            setErrorMessage("Use only letters, digits, '.', '_' and '-' in the repository name.");
            return;
        }
        if (Files.exists(Prefs.folder().resolve(n))) {
            setErrorMessage("A shared model named '" + n + "' already exists in " + Prefs.folder());
            return;
        }
        repoName = n;
        repoOwner = owner.getText().trim();
        privateRepo = isPrivate.getSelection();
        url = u.isEmpty() ? null : CloneDialog.normalizeUrl(u);
        super.okPressed();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Share", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
