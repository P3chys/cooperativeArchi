package io.github.p3chys.cooperativearchi.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;

import io.github.p3chys.cooperativearchi.git.ConflictResolver;
import io.github.p3chys.cooperativearchi.git.HistoryEntry;
import io.github.p3chys.cooperativearchi.git.SharedModel;
import io.github.p3chys.cooperativearchi.git.SyncOptions;
import io.github.p3chys.cooperativearchi.git.SyncResult;
import io.github.p3chys.cooperativearchi.github.GitHubClient;
import io.github.p3chys.cooperativearchi.merge.Conflict;
import io.github.p3chys.cooperativearchi.merge.Conflict.Side;

/**
 * Command line access to shared models (scripting, CI, troubleshooting). Token from COOPARCHI_TOKEN or
 * GITHUB_TOKEN; author from COOPARCHI_NAME / COOPARCHI_EMAIL.
 */
public final class Main {

    private static final String USAGE = """
            usage: cooperativearchi <command> ...
              share  <model.archimate> <workspaceDir> [remoteUrl]   start sharing a model (then run sync)
              share  <model.archimate> <workspaceDir> --github <repoName> [--public]   ...in a new GitHub repository
              clone  <remoteUrl> <workspaceDir>                     download a shared model
              sync   <workspaceDir> [--mine|--theirs] [-m message]  commit, merge, upload
              log    <workspaceDir> [count]                         show history
              branch <workspaceDir> [newBranch]                     list or create branches
              switch <workspaceDir> <branch>                        switch branch (sync first)
              merge  <workspaceDir> <branch> [--mine|--theirs]      merge a branch into the current one
            """;

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(List.of(args)));
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(List<String> args) throws Exception {
        if (args.size() < 2) {
            System.out.print(USAGE);
            return 2;
        }
        String cmd = args.get(0);
        Path ws = Path.of(args.get(cmd.equals("clone") ? 2 : 1));
        switch (cmd) {
            case "share" -> {
                String url = args.size() > 3 ? args.get(3) : null;
                int gh = args.indexOf("--github");
                if (gh >= 0) {
                    String token = env("COOPARCHI_TOKEN", env("GITHUB_TOKEN", null));
                    url = new GitHubClient(token).createRepo(null, args.get(gh + 1), !args.contains("--public"),
                            "Archi model shared with cooperativeArchi").cloneUrl();
                    System.out.println("Created " + url);
                }
                try (SharedModel m = SharedModel.share(Path.of(args.get(1)), Path.of(args.get(2)), url)) {
                    System.out.println("Shared as " + m.modelFile() + " - run 'sync' to upload");
                }
            }
            case "clone" -> {
                try (SharedModel m = SharedModel.cloneFrom(args.get(1), ws, options(args))) {
                    System.out.println("Downloaded " + m.modelFile() + " (branch " + m.branch() + ")");
                }
            }
            case "sync", "merge" -> {
                try (SharedModel m = open(ws)) {
                    SyncOptions o = options(args);
                    if (cmd.equals("merge")) {
                        o = o.withMergeFrom(args.get(2));
                    }
                    SyncResult r = m.sync(o);
                    if (r.needsReload() && !m.applySync(r)) {
                        System.out.println("Model file changed during sync; run sync again");
                    }
                    System.out.println(r.describe());
                    r.notes().forEach(n -> System.out.println("  note: " + n));
                }
            }
            case "log" -> {
                try (SharedModel m = open(ws)) {
                    int count = args.size() > 2 ? Integer.parseInt(args.get(2)) : 20;
                    for (HistoryEntry e : m.history(count)) {
                        System.out.printf("%s %s %-20s %s%s%n", e.shortId(), e.time(), e.author(),
                                e.message().lines().findFirst().orElse(""), e.uploaded() ? "" : "  [not uploaded]");
                    }
                }
            }
            case "branch" -> {
                try (SharedModel m = open(ws)) {
                    if (args.size() > 2) {
                        m.createBranch(args.get(2));
                    }
                    for (String b : m.branches()) {
                        System.out.println((b.equals(m.branch()) ? "* " : "  ") + b);
                    }
                }
            }
            case "switch" -> {
                try (SharedModel m = open(ws)) {
                    if (m.hasLocalChanges()) {
                        throw new IllegalStateException("Sync first: the model has changes that are not synchronized");
                    }
                    m.loadBranchContent(m.switchBranch(args.get(2)));
                    System.out.println("Now on branch " + m.branch());
                }
            }
            default -> {
                System.out.print(USAGE);
                return 2;
            }
        }
        return 0;
    }

    private static SharedModel open(Path ws) throws Exception {
        Path file = Files.isDirectory(ws) ? SharedModel.modelFileIn(ws) : ws;
        return SharedModel.open(file);
    }

    private static SyncOptions options(List<String> args) throws Exception {
        String token = env("COOPARCHI_TOKEN", env("GITHUB_TOKEN", null));
        String remote = args.get(0).equals("clone") ? args.get(1) : remoteOf(Path.of(args.get(1)));
        CredentialsProvider creds = token == null || !GitHubClient.isGitHubUrl(remote) ? null : GitHubClient.credentials(token);
        PersonIdent author = new PersonIdent(env("COOPARCHI_NAME", System.getProperty("user.name")),
                env("COOPARCHI_EMAIL", System.getProperty("user.name") + "@localhost"));
        Side side = args.contains("--theirs") ? Side.THEIRS : args.contains("--mine") ? Side.MINE : null;
        ConflictResolver resolver = side == null ? ConflictResolver.FAIL : (session, label) -> {
            for (Conflict c : session.conflicts()) {
                System.out.println("  conflict: " + c + " -> " + side);
                c.choose(side);
            }
            return true;
        };
        int mi = args.indexOf("-m");
        String message = mi >= 0 && mi + 1 < args.size() ? args.get(mi + 1) : null;
        return SyncOptions.of(creds, author, resolver).withMessage(message);
    }

    private static String remoteOf(Path ws) {
        try (SharedModel m = open(ws)) {
            return m.remoteUrl();
        } catch (Exception e) {
            return null;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
