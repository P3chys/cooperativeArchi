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
import java.util.function.Supplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
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
    /** Saving normally takes well under a second; Windows may need longer to protect the storage key. */
    private static final int SAVE_SECONDS = 20;
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
            new ProgressMonitorDialog(getShell()).run(true, true, monitor -> {
                monitor.beginTask("Contacting GitHub...", IProgressMonitor.UNKNOWN);
                user[0] = await(() -> {
                    try {
                        return new GitHubClient(value).user();
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, monitor, 0);
            });
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
        boolean[] saved = new boolean[1];
        try {
            new ProgressMonitorDialog(getShell()).run(true, true, monitor -> {
                monitor.beginTask("Connected as " + user[0].login() + ". Saving the token...", IProgressMonitor.UNKNOWN);
                saved[0] = Boolean.TRUE.equals(await(() -> Prefs.setToken(value), monitor, SAVE_SECONDS * 1000L));
            });
        } catch (InvocationTargetException | InterruptedException e) {
            // not saved (cancelled, took too long, or failed): setToken already keeps it for this session
            if (e instanceof InvocationTargetException ite) {
                Activator.log("Saving the GitHub token failed", ite.getCause());
            }
        }
        Prefs.put(Prefs.LOGIN, user[0].login());
        if (Prefs.get(Prefs.NAME, "").isBlank()) {
            Prefs.put(Prefs.NAME, user[0].displayName());
        }
        if (Prefs.get(Prefs.EMAIL, "").isBlank()) {
            Prefs.put(Prefs.EMAIL, user[0].commitEmail());
        }
        if (saved[0]) {
            ModelSupport.notify("GitHub", "Connected as " + user[0].login());
        } else {
            MessageDialog.openWarning(getShell(), "GitHub", "Connected as " + user[0].login() + ".\n\n"
                    + "The token could not be saved in Eclipse's secure storage on this computer, so cooperativeArchi"
                    + " uses it until you close Archi and asks for it again next time. Details are in the error log.");
        }
        super.okPressed();
    }

    /**
     * Runs {@code work} in the background and waits for it: the analyst sees progress and can cancel instead of
     * Archi freezing while GitHub, a proxy or secure storage does not answer.
     *
     * @param timeoutMillis 0 for no limit; after it the work is abandoned like a cancel
     */
    private static <T> T await(Supplier<T> work, IProgressMonitor monitor, long timeoutMillis)
            throws InvocationTargetException, InterruptedException {
        CompletableFuture<T> call = CompletableFuture.supplyAsync(work);
        long deadline = timeoutMillis == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
        while (true) {
            if (monitor.isCanceled() || System.currentTimeMillis() > deadline) {
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
