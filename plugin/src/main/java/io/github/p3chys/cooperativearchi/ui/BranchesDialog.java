package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.model.IArchimateModel;

import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.git.SyncOptions;

/**
 * Branches: work on a separate line of the model (e.g. a proposal) and merge it back later.
 * Every operation syncs first so no work is lost.
 */
final class BranchesDialog extends TitleAreaDialog {

    private IArchimateModel model;
    private org.eclipse.swt.widgets.List list;
    private List<String> branches = List.of();
    private String current;

    private BranchesDialog(Shell parent, IArchimateModel model) {
        super(parent);
        this.model = model;
    }

    static void open(IArchimateModel model) {
        if (model == null || !ModelSupport.isShared(model)) {
            MessageDialog.openInformation(ModelSupport.shell(), "Branches", "Select a shared model in the Models tree first.");
            return;
        }
        new BranchesDialog(ModelSupport.shell(), model).open();
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Branches");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Branches of '" + model.getName() + "'");
        setMessage("The model you see is the current branch (marked ▶). Changes stay on their branch until merged.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).indent(10, 10).applyTo(c);
        c.setLayout(new GridLayout(2, false));

        list = new org.eclipse.swt.widgets.List(c, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, true).hint(280, 220).applyTo(list);
        Composite buttons = new Composite(c, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(buttons);
        button(buttons, "New branch...", this::newBranch);
        button(buttons, "Switch to", this::switchTo);
        button(buttons, "Merge into current", this::mergeIntoCurrent);
        button(buttons, "Delete", this::delete);
        list.addListener(SWT.DefaultSelection, e -> switchTo());
        refresh();
        return area;
    }

    private void button(Composite parent, String text, Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        GridDataFactory.fillDefaults().applyTo(b);
        b.addListener(SWT.Selection, e -> {
            setErrorMessage(null);
            action.run();
            refresh();
        });
    }

    private Path file() {
        return model.getFile().toPath();
    }

    private void refresh() {
        try (SharedModel sm = SharedModel.open(file())) {
            branches = sm.branches();
            current = sm.branch();
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            return;
        }
        if (!branches.contains(current)) {
            branches = new java.util.ArrayList<>(branches);
            branches.add(current);
        }
        list.removeAll();
        for (String b : branches) {
            list.add(b.equals(current) ? "▶ " + b + "   (current)" : "   " + b);
        }
    }

    private String selected() {
        int i = list.getSelectionIndex();
        if (i < 0) {
            setErrorMessage("Select a branch first.");
            return null;
        }
        return branches.get(i);
    }

    /** Syncs the current branch so that nothing local is left behind. */
    private boolean syncFirst() {
        SyncRunner.Outcome o = SyncRunner.sync(model, SyncRunner.Mode.INTERACTIVE, null);
        if (o.model() != null) {
            model = o.model();
        }
        if (!o.ok()) {
            return false;
        }
        try (SharedModel sm = SharedModel.open(file())) {
            if (sm.hasLocalChanges() || ModelSupport.isDirty(model)) {
                setErrorMessage("The model still has unsynchronized changes. Sync again first.");
                return false;
            }
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            return false;
        }
        return true;
    }

    private void newBranch() {
        InputDialog d = new InputDialog(getShell(), "New branch",
                "Name of the new branch (starts from the current model, e.g. 'proposal-crm'):", "", v ->
                v.matches("[A-Za-z0-9._/-]+") ? null : "Use letters, digits, '.', '_', '-' or '/'");
        if (d.open() != Window.OK || !syncFirst()) {
            return;
        }
        try (SharedModel sm = SharedModel.open(file())) {
            sm.createBranch(d.getValue());
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            return;
        }
        SyncRunner.sync(model, SyncRunner.Mode.INTERACTIVE, null);
        setMessage("Created and switched to '" + d.getValue() + "'. Your Syncs now go to this branch.");
    }

    private void switchTo() {
        String b = selected();
        if (b == null || b.equals(current) || !syncFirst()) {
            return;
        }
        try (SharedModel sm = SharedModel.open(file())) {
            sm.loadBranchContent(sm.switchBranch(b));
            IArchimateModel reloaded = ModelSupport.reload(model);
            if (reloaded != null) {
                model = reloaded;
            }
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            return;
        }
        SyncRunner.fireChanged();
        setMessage("Switched to '" + b + "'.");
    }

    private void mergeIntoCurrent() {
        String b = selected();
        if (b == null || b.equals(current)) {
            return;
        }
        if (!MessageDialog.openConfirm(getShell(), "Merge", "Merge all changes of '" + b + "' into '" + current + "'?")) {
            return;
        }
        SyncRunner.Outcome o = SyncRunner.sync(model, SyncRunner.Mode.INTERACTIVE, b);
        if (o.model() != null) {
            model = o.model();
        }
        if (o.ok()) {
            setMessage("Merged '" + b + "' into '" + current + "'.");
        }
    }

    private void delete() {
        String b = selected();
        if (b == null) {
            return;
        }
        if (b.equals(current)) {
            setErrorMessage("Switch to another branch before deleting this one.");
            return;
        }
        if (!MessageDialog.openConfirm(getShell(), "Delete branch",
                "Delete branch '" + b + "' for everybody (also on GitHub)?\nChanges not merged elsewhere are lost.")) {
            return;
        }
        try (SharedModel sm = SharedModel.open(file())) {
            sm.deleteBranch(b, SyncOptions.of(
                    SyncRunner.credentials(sm.remoteUrl(), true), Prefs.author(), null));
        } catch (IOException | IllegalStateException e) {
            setErrorMessage(e.getMessage());
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }
}
