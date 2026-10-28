package io.github.p3chys.cooperativearchi.git;

import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;

import io.github.p3chys.cooperativearchi.merge.ChangeSummary;
import io.github.p3chys.cooperativearchi.merge.ModelMerger;
import io.github.p3chys.cooperativearchi.merge.ModelMerger.MergeSession;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.SplitLayout;

/**
 * A model file shared through a git repository.
 *
 * <p>Layout on disk: {@code <workspace>/<name>.archimate} is the file Archi opens;
 * {@code <workspace>/.coarchi} is a bare git repository holding the history in the split format
 * ({@link SplitLayout}). The analyst never sees git: {@link #sync} commits the saved model, merges colleagues'
 * work object by object and uploads the result.
 *
 * <p>Safety: the config value {@code loaded} records which commit the model file on disk corresponds to.
 * Local edits are always committed on top of that commit, so the file is only overwritten when the
 * caller confirms (via {@link #applySync}) that Archi has no unsaved changes.
 */
public final class SharedModel implements AutoCloseable {

    public static final String GIT_DIR = ".coarchi";
    public static final String REMOTE = "origin";
    public static final String DEFAULT_BRANCH = "main";
    private static final String SECTION = "cooperativearchi";
    private static final int PUSH_ATTEMPTS = 5;
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path modelFile;
    private final Repository repo;
    private final Map<ObjectId, ModelGraph> graphCache = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<ObjectId, ModelGraph> eldest) {
            return size() > 8;
        }
    };

    private SharedModel(Path modelFile, Repository repo) {
        this.modelFile = modelFile;
        this.repo = repo;
    }

    // ================================================================ create / open

    public static boolean isShared(Path modelFile) {
        Path parent = modelFile.toAbsolutePath().getParent();
        return parent != null && Files.isDirectory(parent.resolve(GIT_DIR));
    }

    public static SharedModel open(Path modelFile) throws IOException {
        Path file = modelFile.toAbsolutePath().normalize();
        if (!isShared(file)) {
            throw new IOException("Not a shared model: " + file);
        }
        Repository r = new FileRepositoryBuilder().setGitDir(file.getParent().resolve(GIT_DIR).toFile()).setBare().build();
        return new SharedModel(file, r);
    }

    /** The model file of a workspace directory. */
    public static Path modelFileIn(Path workspaceDir) {
        return workspaceDir.toAbsolutePath().normalize().resolve(workspaceDir.getFileName() + ".archimate");
    }

    /**
     * Starts sharing an existing model: copies it into a new workspace. The first {@link #sync} uploads it.
     *
     * @param remoteUrl git URL of an empty repository, or null for local-only history
     */
    public static SharedModel share(Path sourceModel, Path workspaceDir, String remoteUrl) throws IOException {
        ModelGraph.read(Files.readAllBytes(sourceModel)); // validate
        prepareEmptyDir(workspaceDir);
        Path target = modelFileIn(workspaceDir);
        Files.copy(sourceModel, target, StandardCopyOption.REPLACE_EXISTING);
        Repository r = init(workspaceDir, remoteUrl);
        SharedModel m = new SharedModel(target, r);
        m.setBranch(DEFAULT_BRANCH);
        return m;
    }

    /** Downloads a shared model into a new workspace directory. */
    public static SharedModel cloneFrom(String remoteUrl, Path workspaceDir, SyncOptions options) throws IOException {
        prepareEmptyDir(workspaceDir);
        Repository r = init(workspaceDir, remoteUrl);
        SharedModel m = new SharedModel(modelFileIn(workspaceDir), r);
        try {
            FetchResult fetched = m.fetch(options);
            List<String> branches = m.branches();
            if (branches.isEmpty()) {
                throw new IOException("The repository " + remoteUrl + " does not contain a model yet. Use 'Share model' instead.");
            }
            String branch = branches.contains(DEFAULT_BRANCH) ? DEFAULT_BRANCH : branches.get(0);
            Ref head = fetched.getAdvertisedRef(Constants.HEAD);
            if (head != null && head.isSymbolic() && head.getTarget().getName().startsWith(R_HEADS)) {
                branch = head.getTarget().getName().substring(R_HEADS.length());
            }
            ObjectId tip = r.resolve(R_REMOTES + REMOTE + "/" + branch);
            m.setRef(branch, tip);
            m.setBranch(branch);
            m.applyWorkingFile(m.snapshot(tip), tip, null);
            return m;
        } catch (IOException | RuntimeException e) {
            m.close();
            deleteRecursively(workspaceDir);
            throw e;
        }
    }

    private static void prepareEmptyDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var s = Files.list(dir)) {
                if (s.findAny().isPresent()) {
                    throw new IOException("Folder is not empty: " + dir);
                }
            }
        }
        Files.createDirectories(dir);
    }

    private static Repository init(Path workspaceDir, String remoteUrl) throws IOException {
        Path gitDir = workspaceDir.resolve(GIT_DIR);
        Repository r = FileRepositoryBuilder.create(gitDir.toFile());
        r.create(true);
        try {
            Files.setAttribute(gitDir, "dos:hidden", true);
        } catch (UnsupportedOperationException | IOException e) {
            // not Windows
        }
        RefUpdate head = r.updateRef(Constants.HEAD);
        head.link(R_HEADS + DEFAULT_BRANCH);
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            setRemote(r, remoteUrl);
        }
        return r;
    }

    private static void setRemote(Repository r, String url) throws IOException {
        StoredConfig cfg = r.getConfig();
        try {
            RemoteConfig rc = new RemoteConfig(cfg, REMOTE);
            List.copyOf(rc.getURIs()).forEach(rc::removeURI);
            List.copyOf(rc.getFetchRefSpecs()).forEach(rc::removeFetchRefSpec);
            rc.addURI(new URIish(url));
            rc.addFetchRefSpec(new RefSpec("+refs/heads/*:refs/remotes/" + REMOTE + "/*"));
            rc.update(cfg);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid repository URL: " + url, e);
        }
        cfg.save();
    }

    // ================================================================ state

    public Path modelFile() {
        return modelFile;
    }

    public String branch() {
        String b = repo.getConfig().getString(SECTION, null, "branch");
        return b == null ? DEFAULT_BRANCH : b;
    }

    private void setBranch(String branch) throws IOException {
        StoredConfig cfg = repo.getConfig();
        cfg.setString(SECTION, null, "branch", branch);
        cfg.save();
        RefUpdate head = repo.updateRef(Constants.HEAD);
        head.link(R_HEADS + branch);
    }

    public String remoteUrl() {
        return repo.getConfig().getString("remote", REMOTE, "url");
    }

    public void setRemoteUrl(String url) throws IOException {
        setRemote(repo, url);
    }

    private ObjectId loadedId() {
        String id = repo.getConfig().getString(SECTION, null, "loaded");
        return id == null ? null : ObjectId.fromString(id);
    }

    private void setLoaded(ObjectId id) throws IOException {
        StoredConfig cfg = repo.getConfig();
        cfg.setString(SECTION, null, "loaded", id.name());
        cfg.save();
    }

    private String pendingMessage() {
        return repo.getConfig().getString(SECTION, null, "pendingMessage");
    }

    private void setPendingMessage(String message) throws IOException {
        StoredConfig cfg = repo.getConfig();
        if (message == null) {
            cfg.unset(SECTION, null, "pendingMessage");
        } else {
            cfg.setString(SECTION, null, "pendingMessage", message);
        }
        cfg.save();
    }

    /** True if the saved model file differs from the last synced version. */
    public boolean hasLocalChanges() throws IOException {
        ObjectId loaded = loadedId();
        if (loaded == null) {
            return true;
        }
        try (ObjectInserter ins = repo.newObjectInserter()) {
            return !writeTree(SplitLayout.write(ModelGraph.read(Files.readAllBytes(modelFile))), ins).equals(treeOf(loaded));
        }
    }

    // ================================================================ sync

    /**
     * Commits the saved model file, merges the local branch, an optional other branch and the remote branch,
     * and uploads the result. Does not touch the model file: call {@link #applySync} if
     * {@link SyncResult#needsReload()} and Archi has no unsaved changes.
     */
    public SyncResult sync(SyncOptions options) throws IOException, SyncCancelledException {
        ReentrantLock lock = LOCKS.computeIfAbsent(modelFile, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IOException("This model is already being synchronized");
        }
        try {
            return doSync(options);
        } finally {
            lock.unlock();
        }
    }

    private SyncResult doSync(SyncOptions o) throws IOException, SyncCancelledException {
        SyncResult res = new SyncResult();
        byte[] bytes = Files.readAllBytes(modelFile);
        res.fileHashAtRead = sha256(bytes);
        Map<String, byte[]> files = SplitLayout.write(ModelGraph.read(bytes));

        try (ObjectInserter ins = repo.newObjectInserter()) {
            ObjectId localTree = writeTree(files, ins);
            ObjectId loaded = loadedId();
            ObjectId local = loaded;
            if (loaded == null || !treeOf(loaded).equals(localTree)) {
                ModelGraph before = loaded == null ? null : graph(loaded);
                ModelGraph saved = SplitLayout.read(files);
                String summary = loaded == null ? "First version of '" + saved.root().name() + "'"
                        : ChangeSummary.of(before, saved).toString();
                String message = o.message() != null && !o.message().isBlank() ? o.message().trim()
                        : pendingMessage() != null ? pendingMessage() + "\n\n" + summary : summary;
                local = commit(ins, localTree, message, o.author(), loaded == null ? new ObjectId[0] : new ObjectId[] { loaded });
                res.committed = summary;
                setLoaded(local);
                setPendingMessage(null);
            }

            String branch = branch();
            ObjectId result = mergeCommits(ins, local, repo.resolve(R_HEADS + branch), "your earlier local version", o, res);
            if (o.mergeFrom() != null) {
                ObjectId other = resolveBranch(o.mergeFrom());
                if (other == null) {
                    throw new IOException("Unknown branch: " + o.mergeFrom());
                }
                result = mergeCommits(ins, result, other, "branch '" + o.mergeFrom() + "'", o, res);
            }
            setRef(branch, result);

            if (remoteUrl() != null) {
                for (int attempt = 1;; attempt++) {
                    try {
                        fetch(o);
                    } catch (IOException e) {
                        if (isAuthProblem(e)) {
                            throw e;
                        }
                        res.offlineReason = e.getMessage();
                        break;
                    }
                    ObjectId remote = repo.resolve(R_REMOTES + REMOTE + "/" + branch);
                    result = mergeCommits(ins, result, remote, "colleagues' changes", o, res);
                    setRef(branch, result);
                    if (result.equals(remote)) {
                        break;
                    }
                    RemoteRefUpdate.Status status = push(branch, o);
                    if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE) {
                        res.pushed = true;
                        break;
                    }
                    if (attempt >= PUSH_ATTEMPTS) {
                        throw new IOException("Upload kept being rejected (" + status + "), please try again");
                    }
                }
            }

            res.newHead = result;
            if (!treeOf(result).equals(localTree)) {
                res.received = ChangeSummary.of(graph(local), graph(result)).toString();
                res.modelToLoad = snapshot(result);
            } else if (!result.equals(local)) {
                setLoaded(result);
            }
            return res;
        }
    }

    /**
     * Writes the merged model to the model file. Refuses (returns false) if the file changed since the sync
     * read it, so a save made during the sync is never overwritten; the next sync will merge it instead.
     */
    public boolean applySync(SyncResult result) throws IOException {
        if (!result.needsReload()) {
            return true;
        }
        return applyWorkingFile(result.modelToLoad, result.newHead, result.fileHashAtRead);
    }

    private boolean applyWorkingFile(byte[] content, ObjectId commit, byte[] expectedHash) throws IOException {
        if (expectedHash != null && Files.exists(modelFile)
                && !Arrays.equals(expectedHash, sha256(Files.readAllBytes(modelFile)))) {
            return false;
        }
        Path tmp = modelFile.resolveSibling(modelFile.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, modelFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        setLoaded(commit);
        return true;
    }

    private ObjectId mergeCommits(ObjectInserter ins, ObjectId mine, ObjectId theirs, String label, SyncOptions o,
            SyncResult res) throws IOException, SyncCancelledException {
        if (theirs == null || theirs.equals(mine)) {
            return mine;
        }
        if (mine == null) {
            return theirs;
        }
        try (RevWalk w = new RevWalk(repo)) {
            RevCommit a = w.parseCommit(mine);
            RevCommit b = w.parseCommit(theirs);
            if (w.isMergedInto(b, a)) {
                return mine;
            }
            w.reset();
            if (w.isMergedInto(a, b)) {
                return theirs;
            }
        }
        ObjectId base = mergeBase(mine, theirs);
        ModelGraph gm = graph(mine);
        ModelGraph gt = graph(theirs);
        ModelGraph gb = base == null ? new ModelGraph(gm.rootId(), Map.of(), Map.of()) : graph(base);
        MergeSession session;
        try {
            session = ModelMerger.prepare(gb, gm, gt);
        } catch (IllegalArgumentException e) {
            throw new IOException("The repository contains a different model (" + e.getMessage() + ")");
        }
        if (!session.conflicts().isEmpty()) {
            if (!o.resolver().resolve(session, label)) {
                throw new SyncCancelledException();
            }
            res.conflictsResolved += session.conflicts().size();
        }
        ModelGraph merged = session.finish();
        res.notes.addAll(session.notes());
        ObjectId tree = writeTree(SplitLayout.write(merged), ins);
        String message = "Merge " + label + ": " + ChangeSummary.of(gm, merged);
        return commit(ins, tree, message, o.author(), new ObjectId[] { mine, theirs });
    }

    private ObjectId mergeBase(ObjectId a, ObjectId b) throws IOException {
        try (RevWalk w = new RevWalk(repo)) {
            w.setRevFilter(RevFilter.MERGE_BASE);
            w.markStart(w.parseCommit(a));
            w.markStart(w.parseCommit(b));
            RevCommit base = w.next();
            return base == null ? null : base.getId();
        }
    }

    private FetchResult fetch(SyncOptions o) throws IOException {
        try {
            return Git.wrap(repo).fetch().setRemote(REMOTE).setCredentialsProvider(o.credentials())
                    .setProgressMonitor(o.monitor()).setRemoveDeletedRefs(true).call();
        } catch (TransportException e) {
            throw new IOException(friendly(e), e);
        } catch (GitAPIException e) {
            throw new IOException("Download failed: " + e.getMessage(), e);
        }
    }

    private RemoteRefUpdate.Status push(String branch, SyncOptions o) throws IOException {
        try {
            Iterable<PushResult> results = Git.wrap(repo).push().setRemote(REMOTE).setCredentialsProvider(o.credentials())
                    .setProgressMonitor(o.monitor()).setRefSpecs(new RefSpec(R_HEADS + branch + ":" + R_HEADS + branch))
                    .call();
            for (PushResult pr : results) {
                for (RemoteRefUpdate u : pr.getRemoteUpdates()) {
                    switch (u.getStatus()) {
                        case OK, UP_TO_DATE, REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> {
                            return u.getStatus();
                        }
                        default -> throw new IOException("Upload rejected: " + u.getStatus()
                                + (u.getMessage() == null ? "" : " - " + u.getMessage()));
                    }
                }
            }
            throw new IOException("Upload failed: no result");
        } catch (TransportException e) {
            throw new IOException(friendly(e), e);
        } catch (GitAPIException e) {
            throw new IOException("Upload failed: " + e.getMessage(), e);
        }
    }

    private static String friendly(TransportException e) {
        String m = String.valueOf(e.getMessage());
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("not authorized") || lower.contains("authentication") || lower.contains("401")
                || lower.contains("403") || lower.contains("credentials")) {
            return "GitHub rejected the access token (check it in Preferences > cooperativeArchi): " + m;
        }
        if (lower.contains("not found") || lower.contains("404")) {
            return "Repository not found or no access: " + m;
        }
        return "Cannot reach the server: " + m;
    }

    private static boolean isAuthProblem(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.startsWith("GitHub rejected") || m.startsWith("Repository not found");
    }

    // ================================================================ branches

    /** Local and remote branch names, sorted. */
    public List<String> branches() throws IOException {
        Set<String> names = new TreeSet<>();
        for (Ref r : repo.getRefDatabase().getRefsByPrefix(R_HEADS)) {
            names.add(r.getName().substring(R_HEADS.length()));
        }
        String remotePrefix = R_REMOTES + REMOTE + "/";
        for (Ref r : repo.getRefDatabase().getRefsByPrefix(remotePrefix)) {
            String n = r.getName().substring(remotePrefix.length());
            if (!n.equals(Constants.HEAD)) {
                names.add(n);
            }
        }
        return new ArrayList<>(names);
    }

    private ObjectId resolveBranch(String name) throws IOException {
        ObjectId local = repo.resolve(R_HEADS + name);
        return local != null ? local : repo.resolve(R_REMOTES + REMOTE + "/" + name);
    }

    /** Creates a branch from the current state and makes it current; it is uploaded on the next sync. */
    public void createBranch(String name) throws IOException {
        if (!Repository.isValidRefName(R_HEADS + name) || name.contains(" ")) {
            throw new IOException("Invalid branch name: " + name);
        }
        if (branches().contains(name)) {
            throw new IOException("Branch already exists: " + name);
        }
        ObjectId start = repo.resolve(R_HEADS + branch());
        if (start == null) {
            start = loadedId();
        }
        if (start == null) {
            throw new IOException("Sync the model once before creating branches");
        }
        setRef(name, start);
        setBranch(name);
    }

    /**
     * Makes another branch current and returns its model content. The caller must have synced first
     * (so no local work is lost) and must load the returned content with {@link #loadBranchContent}.
     */
    public byte[] switchBranch(String name) throws IOException {
        ObjectId target = resolveBranch(name);
        if (target == null) {
            throw new IOException("Unknown branch: " + name);
        }
        if (repo.resolve(R_HEADS + name) == null) {
            setRef(name, target);
        }
        setBranch(name);
        return snapshot(target);
    }

    /** Writes the current branch's content to the model file (after {@link #switchBranch}). */
    public void loadBranchContent(byte[] content) throws IOException {
        applyWorkingFile(content, repo.resolve(R_HEADS + branch()), null);
    }

    public void deleteBranch(String name, SyncOptions o) throws IOException {
        if (name.equals(branch())) {
            throw new IOException("Cannot delete the current branch");
        }
        if (repo.resolve(R_REMOTES + REMOTE + "/" + name) != null && remoteUrl() != null) {
            try {
                Git.wrap(repo).push().setRemote(REMOTE).setCredentialsProvider(o.credentials())
                        .setRefSpecs(new RefSpec(":" + R_HEADS + name)).call();
                RefUpdate ru = repo.updateRef(R_REMOTES + REMOTE + "/" + name);
                ru.setForceUpdate(true);
                ru.delete();
            } catch (GitAPIException e) {
                throw new IOException("Could not delete the branch on the server: " + e.getMessage(), e);
            }
        }
        if (repo.resolve(R_HEADS + name) != null) {
            RefUpdate ru = repo.updateRef(R_HEADS + name);
            ru.setForceUpdate(true);
            ru.delete();
        }
    }

    // ================================================================ history

    public List<HistoryEntry> history(int max) throws IOException {
        ObjectId head = repo.resolve(R_HEADS + branch());
        ObjectId remote = repo.resolve(R_REMOTES + REMOTE + "/" + branch());
        ObjectId loaded = loadedId();
        Set<ObjectId> uploaded = new HashSet<>();
        if (remote != null) {
            try (RevWalk w = new RevWalk(repo)) {
                w.markStart(w.parseCommit(remote));
                int n = 0;
                for (RevCommit c : w) {
                    uploaded.add(c.getId());
                    if (++n > max * 4) {
                        break;
                    }
                }
            }
        }
        List<HistoryEntry> list = new ArrayList<>();
        try (RevWalk w = new RevWalk(repo)) {
            w.sort(RevSort.COMMIT_TIME_DESC);
            for (ObjectId start : new ObjectId[] { head, remote, loaded }) {
                if (start != null) {
                    w.markStart(w.parseCommit(start));
                }
            }
            for (RevCommit c : w) {
                PersonIdent a = c.getAuthorIdent();
                list.add(new HistoryEntry(c.getId(), c.getFullMessage().trim(), a.getName(), a.getEmailAddress(),
                        a.getWhenAsInstant(), c.getParentCount() > 1, uploaded.contains(c.getId()), c.getId().equals(loaded)));
                if (list.size() >= max) {
                    break;
                }
            }
        }
        return list;
    }

    /** The model as it was in a version, as .archimate bytes (to open it as a copy). */
    public byte[] modelAt(ObjectId commit) throws IOException {
        return snapshot(commit);
    }

    /**
     * Replaces the model file with an old version. The next sync uploads this as a new change on top of the
     * history (nothing is rewritten), so colleagues receive it like any other edit.
     */
    public void restoreVersion(ObjectId commit, Instant when) throws IOException {
        byte[] content = snapshot(commit);
        setPendingMessage("Restored version " + commit.abbreviate(7).name() + " from " + when);
        Path tmp = modelFile.resolveSibling(modelFile.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, modelFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ================================================================ git plumbing

    private ObjectId treeOf(ObjectId commit) throws IOException {
        try (RevWalk w = new RevWalk(repo)) {
            return w.parseCommit(commit).getTree().getId();
        }
    }

    private ModelGraph graph(ObjectId commit) throws IOException {
        ObjectId tree = treeOf(commit);
        ModelGraph cached = graphCache.get(tree);
        if (cached == null) {
            cached = SplitLayout.read(readTree(tree));
            graphCache.put(tree, cached);
        }
        return cached;
    }

    private Map<String, byte[]> readTree(ObjectId tree) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            while (tw.next()) {
                String path = tw.getPathString();
                if (!path.equals(SplitLayout.SNAPSHOT_FILE)) {
                    files.put(path, repo.open(tw.getObjectId(0)).openStream().readAllBytes());
                }
            }
        }
        return files;
    }

    private byte[] snapshot(ObjectId commit) throws IOException {
        ObjectId tree = treeOf(commit);
        try (TreeWalk tw = TreeWalk.forPath(repo, SplitLayout.SNAPSHOT_FILE, tree)) {
            if (tw != null) {
                return repo.open(tw.getObjectId(0)).openStream().readAllBytes();
            }
        }
        return SplitLayout.read(readTree(tree)).write();
    }

    private static ObjectId writeTree(Map<String, byte[]> files, ObjectInserter ins) throws IOException {
        DirCache dc = DirCache.newInCore();
        DirCacheBuilder builder = dc.builder();
        for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
            DirCacheEntry e = new DirCacheEntry(f.getKey());
            e.setFileMode(FileMode.REGULAR_FILE);
            e.setObjectId(ins.insert(Constants.OBJ_BLOB, f.getValue()));
            builder.add(e);
        }
        builder.finish();
        ObjectId id = dc.writeTree(ins);
        ins.flush();
        return id;
    }

    private static ObjectId commit(ObjectInserter ins, ObjectId tree, String message, PersonIdent author,
            ObjectId[] parents) throws IOException {
        PersonIdent who = new PersonIdent(author.getName(), author.getEmailAddress());
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(tree);
        cb.setParentIds(parents);
        cb.setAuthor(who);
        cb.setCommitter(who);
        cb.setEncoding(StandardCharsets.UTF_8);
        cb.setMessage(message);
        ObjectId id = ins.insert(cb);
        ins.flush();
        return id;
    }

    private void setRef(String branch, ObjectId id) throws IOException {
        RefUpdate u = repo.updateRef(R_HEADS + branch);
        u.setNewObjectId(id);
        u.setForceUpdate(true);
        u.setRefLogMessage("cooperativeArchi", false);
        switch (u.update()) {
            case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> {
            }
            default -> throw new IOException("Could not update branch " + branch + ": " + u.getResult());
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }

    @Override
    public void close() {
        repo.close();
    }
}
