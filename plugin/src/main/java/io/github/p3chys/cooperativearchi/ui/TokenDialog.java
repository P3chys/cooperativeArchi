package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import io.github.p3chys.cooperativearchi.github.GitHubClient;

/** Asks for a GitHub personal access token, verifies it and remembers the analyst's name for the history. */
final class TokenDialog extends TitleAreaDialog {

    static final String TOKEN_URL = "https://github.com/settings/personal-access-tokens/new";
    private Text token;

    private TokenDialog(Shell parent) {
        super(parent);
    }

    /** Shows the dialog; returns true if a valid token was stored. */
    static boolean ask(Shell parent) {
        return new TokenDialog(parent).open() == OK;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("GitHub access token");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Connect to GitHub");
        setMessage("cooperativeArchi uses a personal access token to upload and download models.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).indent(10, 10).applyTo(c);
        c.setLayout(new GridLayout(2, false));

        Link help = new Link(c, SWT.WRAP);
        help.setText("""
                1. Open <a href="%s">GitHub > Settings > Developer settings > Personal access tokens</a>
                2. Choose the repositories (or "All repositories") of your team.
                3. Repository permissions: Contents = Read and write, Administration = Read and write
                    (Administration is only needed to create new repositories with 'Share Model').
                4. Generate the token, copy it and paste it below. It is stored encrypted on this computer.""".formatted(TOKEN_URL));
        help.addListener(SWT.Selection, e -> Program.launch(e.text));
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(560, SWT.DEFAULT).applyTo(help);

        new Label(c, SWT.NONE).setText("Token:");
        token = new Text(c, SWT.BORDER | SWT.PASSWORD);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(token);
        String existing = Prefs.token();
        if (existing != null) {
            token.setText(existing);
        }
        return area;
    }

    @Override
    protected void okPressed() {
        String value = token.getText().trim();
        if (value.isEmpty()) {
            setErrorMessage("Paste a token first.");
            return;
        }
        GitHubClient.User[] user = new GitHubClient.User[1];
        try {
            new ProgressMonitorDialog(getShell()).run(true, true, monitor -> user[0] = verify(value, monitor));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (!(cause instanceof IOException)) {
                Activator.log("Verifying the GitHub token failed", cause);
            }
            setErrorMessage("Token not accepted: " + describe(cause));
            return;
        } catch (InterruptedException e) {
            setErrorMessage("Cancelled. GitHub did not answer; check the network or proxy settings.");
            return;
        }
        // saving may open Eclipse's secure storage password prompt; say where we are in case it hides behind Archi
        setMessage("Connected as " + user[0].login() + ". Saving the token in Eclipse's secure storage...");
        getShell().update();
        Prefs.setToken(value);
        Prefs.put(Prefs.LOGIN, user[0].login());
        if (Prefs.get(Prefs.NAME, "").isBlank()) {
            Prefs.put(Prefs.NAME, user[0].displayName());
        }
        if (Prefs.get(Prefs.EMAIL, "").isBlank()) {
            Prefs.put(Prefs.EMAIL, user[0].commitEmail());
        }
        ModelSupport.notify("GitHub", "Connected as " + user[0].login());
        super.okPressed();
    }

    /**
     * Asks GitHub who the token belongs to, in the background: the analyst sees progress and can cancel instead of
     * Archi freezing while GitHub (or a proxy) does not answer.
     */
    private static GitHubClient.User verify(String token, IProgressMonitor monitor)
            throws InvocationTargetException, InterruptedException {
        monitor.beginTask("Contacting GitHub...", IProgressMonitor.UNKNOWN);
        CompletableFuture<GitHubClient.User> call = CompletableFuture.supplyAsync(() -> {
            try {
                return new GitHubClient(token).user();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        while (true) {
            if (monitor.isCanceled()) {
                call.cancel(true);
                throw new InterruptedException();
            }
            try {
                return call.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // still waiting
            } catch (ExecutionException e) {
                throw new InvocationTargetException(e.getCause());
            }
        }
    }

    /** The error and what caused it, so unexpected failures (not just I/O errors) are readable in the dialog. */
    static String describe(Throwable t) {
        List<String> parts = new ArrayList<>();
        for (Throwable c = t; c != null && parts.size() < 4; c = c.getCause()) {
            String m = c instanceof IOException && c.getMessage() != null ? c.getMessage()
                    : c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
            if (parts.stream().noneMatch(p -> p.contains(m))) {
                parts.add(m);
            }
        }
        return String.join(" - caused by ", parts);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Verify and save", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
