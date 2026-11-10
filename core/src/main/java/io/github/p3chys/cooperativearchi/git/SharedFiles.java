package io.github.p3chys.cooperativearchi.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import io.github.p3chys.cooperativearchi.merge.ModelDiff;

/**
 * The {@code Assets} folder next to the model file (pictures and documents the model's descriptions refer to),
 * shared with the model: its files travel in the repository under {@code Assets/}, whole, and are merged file by
 * file. Nothing is ever lost: when both sides changed a file, both versions are kept (one under a "conflict" name);
 * a file deleted on one side and changed on the other stays.
 *
 * <p>A version written by a plugin that knows about assets carries the file {@link #MARKER}. A version without it
 * (written by an older plugin, which drops what it doesn't know) says nothing about assets: it has those of the
 * version it was made from ({@link #at}). So a colleague on an older plugin never deletes anyone's assets.
 *
 * <p>A few files next to the model file are shared the same way ({@link #ROOT_FILES}): settings that other tools
 * keep with a model. This plugin knows their names, not what is in them.
 *
 * <p>Paths come from the repository, i.e. from other people: a file is only ever written inside the Assets folder
 * or as one of those files, never through a link, and only under a name every operating system accepts
 * ({@link #valid}).
 */
final class SharedFiles {

    static final String DIR = "Assets";
    static final String PREFIX = DIR + "/";
    /** Files next to the model file that are shared like the assets. */
    static final Set<String> ROOT_FILES = Set.of("documentation.rules", "documentation.published");
    private static final Pattern CONFLICT_COPY = Pattern.compile("(.*) \\(conflict [0-9a-f]{7,40}\\)(\\.[^.]*)?");
    /** In the repository only, never on disk. */
    static final String MARKER = ".coarchi-assets";
    /** GitHub refuses files over 100 MB; half of that keeps uploads and the repository reasonable. */
    static final long MAX_SIZE = 50L * 1024 * 1024;

    private static final byte[] MARKER_TEXT = ("The Assets folder is shared with this model by cooperativeArchi.\n"
            + "Without this file, a version says nothing about assets (it was written by an older plugin).\n")
            .getBytes(StandardCharsets.UTF_8);
    /** Longer than the coarsest file time (2 s on FAT, 1 s on some others). */
    private static final long SETTLED = 3000;
    private static final String TEMP_SUFFIX = ".coarchi-tmp";
    private static final Set<String> JUNK = Set.of("thumbs.db", ".ds_store", "desktop.ini");
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");
    private static final String SECTION = "cooperativearchi";
    /** No version up to this one carries assets: where the search for one that does can stop. */
    private static final String NONE_UP_TO = "noAssetsUpTo";

    /** The assets of a version, by repository path. {@code shared}: written by a plugin that knows about assets. */
    record Assets(SortedMap<String, ObjectId> files, boolean shared) {
        static final Assets NONE = new Assets(Collections.emptySortedMap(), false);
    }

    /**
     * A file's content id with the size and time it had. It is believed only if the file was already older than
     * {@link #SETTLED} when it was read: a file changed twice within the clock's step keeps its time, and with the
     * same size the second change would go unseen.
     */
    private record Stamp(long size, long modified, long taken, ObjectId id) {

        boolean holds(BasicFileAttributes attrs) {
            return size == attrs.size() && modified == attrs.lastModifiedTime().toMillis() && modified + SETTLED < taken;
        }
    }

    // content ids of files on disk, so a sync reads only the files that changed
    private static final Map<Path, Stamp> STAMPS = new ConcurrentHashMap<>();
    // commits are content-addressed, so these are shared by all instances and never go stale
    private static final Map<ObjectId, Assets> AT = lru(64);
    private static final Map<ObjectId, Boolean> CARRIED = lru(256);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final Repository repo;
    private final Path workspace;
    private final Path dir;

    SharedFiles(Repository repo, Path workspace) {
        this.repo = repo;
        this.workspace = workspace;
        this.dir = workspace.resolve(DIR);
    }

    /** One of {@link #ROOT_FILES}, or the conflict copy of one (see {@link #conflictPath}). */
    static boolean rootFile(String path) {
        if (path.indexOf('/') >= 0) {
            return false;
        }
        java.util.regex.Matcher copy = CONFLICT_COPY.matcher(path);
        return ROOT_FILES.contains(path)
                || (copy.matches() && ROOT_FILES.contains(copy.group(1) + (copy.group(2) == null ? "" : copy.group(2))));
    }

    /** Where a shared file is on this computer. */
    private Path file(String path) {
        return path.startsWith(PREFIX) ? dir.resolve(path.substring(PREFIX.length())) : workspace.resolve(path);
    }

    private static <K, V> Map<K, V> lru(int size) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        });
    }

    // ================================================================ in the repository

    /**
     * The assets of a version. One that carries none itself (older plugin) has those of the version it was made
     * from; made by merging two, what merging their assets gives.
     */
    Assets at(ObjectId commit) throws IOException {
        return at(commit, new java.util.HashMap<>(), 0);
    }

    /** Merges of versions without assets nested deeper than this are read along their first parent only. */
    private static final int MAX_DEPTH = 64;

    /**
     * @param seen  what this call worked out already, so no version is worked out twice whatever the history is
     * @param depth how many merges without assets lead here: the history comes from other people and is bounded
     */
    private Assets at(ObjectId commit, Map<ObjectId, Assets> seen, int depth) throws IOException {
        if (commit == null) {
            return Assets.NONE;
        }
        List<ObjectId> passed = new ArrayList<>();
        Assets assets = null;
        try (RevWalk w = new RevWalk(repo)) {
            ObjectId current = commit;
            boolean carried = false; // true of a version is true of the one it was made from, if that one carries none
            while (assets == null) {
                assets = seen.containsKey(current) ? seen.get(current) : AT.get(current);
                if (assets != null) {
                    break;
                }
                passed.add(current.copy());
                RevCommit c = w.parseCommit(current);
                if (carries(c.getTree())) {
                    assets = read(c.getTree());
                } else if (c.getParentCount() == 0 || !(carried = carried || carriedBefore(current))) {
                    assets = Assets.NONE;
                } else if (c.getParentCount() == 1 || depth >= MAX_DEPTH) {
                    current = c.getParent(0);
                } else {
                    ObjectId base = mergeBase(c.getParent(0), c.getParent(1));
                    assets = merge(at(base, seen, depth + 1), at(c.getParent(0), seen, depth + 1),
                            at(c.getParent(1), seen, depth + 1), null, new ArrayList<>());
                }
            }
        }
        for (ObjectId id : passed) {
            seen.put(id, assets);
            AT.put(id, assets);
        }
        return assets;
    }

    /** True if the version itself carries assets (false: it has those of the version it was made from). */
    boolean carries(ObjectId commit) throws IOException {
        try (RevWalk w = new RevWalk(repo)) {
            return carries(w.parseCommit(commit).getTree());
        }
    }

    private boolean carries(RevTree tree) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(repo, MARKER, tree)) {
            return tw != null;
        }
    }

    /** True if this version or one before it carries assets. Most histories never do: remembered, so asked once. */
    private boolean carriedBefore(ObjectId commit) throws IOException {
        Boolean known = CARRIED.get(commit);
        if (known != null) {
            return known;
        }
        boolean found = false;
        StoredConfig cfg = repo.getConfig();
        try (RevWalk w = new RevWalk(repo)) {
            w.markStart(w.parseCommit(commit));
            String none = cfg.getString(SECTION, null, NONE_UP_TO);
            if (none != null && ObjectId.isId(none)) {
                try {
                    w.markUninteresting(w.parseCommit(ObjectId.fromString(none)));
                } catch (MissingObjectException e) {
                    // not in this repository (any more): search it all
                }
            }
            for (RevCommit c : w) {
                if (carries(c.getTree())) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            cfg.setString(SECTION, null, NONE_UP_TO, commit.name());
            cfg.save();
        }
        CARRIED.put(commit.copy(), found);
        return found;
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

    private Assets read(RevTree tree) throws IOException {
        SortedMap<String, ObjectId> files = new TreeMap<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(DIR));
            while (tw.next()) {
                if (tw.getFileMode(0) == FileMode.REGULAR_FILE || tw.getFileMode(0) == FileMode.EXECUTABLE_FILE) {
                    files.put(tw.getPathString(), tw.getObjectId(0));
                }
            }
        }
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(false);
            while (tw.next()) {
                if (!tw.isSubtree() && rootFile(tw.getNameString())) {
                    files.put(tw.getNameString(), tw.getObjectId(0));
                }
            }
        }
        return new Assets(Collections.unmodifiableSortedMap(files), true);
    }

    /** Adds the assets to a tree being written. */
    static void addTo(DirCacheBuilder builder, Assets assets, ObjectInserter ins) throws IOException {
        if (!assets.shared()) {
            return;
        }
        DirCacheEntry marker = new DirCacheEntry(MARKER);
        marker.setFileMode(FileMode.REGULAR_FILE);
        marker.setObjectId(ins.insert(Constants.OBJ_BLOB, MARKER_TEXT));
        builder.add(marker);
        for (Map.Entry<String, ObjectId> f : assets.files().entrySet()) {
            DirCacheEntry e = new DirCacheEntry(f.getKey());
            e.setFileMode(FileMode.REGULAR_FILE);
            e.setObjectId(f.getValue());
            builder.add(e);
        }
    }

    /** True for a repository path that belongs to the assets, not to the model. */
    static boolean owns(String path) {
        return path.equals(MARKER) || path.startsWith(PREFIX) || rootFile(path);
    }

    // ================================================================ merge

    /**
     * Merges file by file. A file changed on one side only is taken from that side. Changed differently on both:
     * {@code theirs} keeps the name and {@code mine} is kept next to it under a conflict name, the same whoever
     * merges. Deleted on one side and changed on the other: it stays.
     *
     * @param label whose changes {@code theirs} are, for the notes; null for no notes
     */
    static Assets merge(Assets base, Assets mine, Assets theirs, String label, List<String> notes) {
        Set<String> paths = new TreeSet<>(base.files().keySet());
        paths.addAll(mine.files().keySet());
        paths.addAll(theirs.files().keySet());
        SortedMap<String, ObjectId> out = new TreeMap<>();
        for (String path : paths) {
            ObjectId b = base.files().get(path);
            ObjectId m = mine.files().get(path);
            ObjectId t = theirs.files().get(path);
            ObjectId result;
            if (Objects.equals(m, t) || Objects.equals(t, b)) {
                result = m;
            } else if (Objects.equals(m, b)) {
                result = t;
            } else if (m == null || t == null) {
                result = m == null ? t : m;
                note(notes, label, label(path) + " was deleted on one side and changed on the other: it is kept");
            } else {
                result = t;
                String copy = conflictPath(path, m, paths);
                out.put(copy, m);
                note(notes, label, label(path) + " was changed here and in " + label + ": that version keeps the "
                        + "name, yours is saved as '" + name(copy) + "'");
            }
            if (result != null) {
                out.put(path, result);
            }
        }
        return new Assets(Collections.unmodifiableSortedMap(out),
                base.shared() || mine.shared() || theirs.shared() || !out.isEmpty());
    }

    private static void note(List<String> notes, String label, String text) {
        if (label != null) {
            notes.add(text);
        }
    }

    /** "Assets/a/flow.png" to "Assets/a/flow (conflict 1a2b3c4).png": named after the content, so it is stable. */
    static String conflictPath(String path, ObjectId content, Set<String> taken) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String stem = dot > slash + 1 ? path.substring(0, dot) : path;
        String extension = dot > slash + 1 ? path.substring(dot) : "";
        for (int length = 7;; length++) {
            String candidate = stem + " (conflict " + content.name().substring(0, length) + ")" + extension;
            if (!taken.contains(candidate) || length >= Constants.OBJECT_ID_STRING_LENGTH) {
                return candidate;
            }
        }
    }

    // ================================================================ on disk

    /**
     * The files of the Assets folder as they are now; new content goes into the repository. Links are never followed
     * and never shared. A file that cannot be read or is too large stays shared as it was, and so does one this
     * computer cannot hold at all (see {@link #storable}): none of them counts as deleted.
     *
     * @param known the assets the folder was last brought up to
     */
    Assets scan(Assets known, ObjectInserter ins, List<String> notes) throws IOException {
        SortedMap<String, ObjectId> files = new TreeMap<>();
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    take(file, attrs, PREFIX + dir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/"),
                            known, files, ins, notes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        try (var beside = Files.list(workspace)) {
            for (Path file : (Iterable<Path>) beside::iterator) {
                String name = file.getFileName().toString();
                if (rootFile(name)) {
                    take(file, Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), name, known,
                            files, ins, notes);
                }
            }
        }
        for (Map.Entry<String, ObjectId> k : known.files().entrySet()) {
            if (!files.containsKey(k.getKey()) && !storable(k.getKey(), known.files().keySet())) {
                files.put(k.getKey(), k.getValue());
            }
        }
        return new Assets(Collections.unmodifiableSortedMap(files), known.shared() || !files.isEmpty());
    }

    /** One file found on disk: its content id, or as it was shared before if it cannot be taken now. */
    private void take(Path file, BasicFileAttributes attrs, String path, Assets known, Map<String, ObjectId> files,
            ObjectInserter ins, List<String> notes) {
        ObjectId before = known.files().get(path);
        if (!attrs.isRegularFile() || (before == null && junk(file.getFileName().toString()))) {
            return;
        }
        if (!valid(path)) {
            report(notes, label(path) + " is not shared: its name is not usable on every computer");
        } else if (attrs.size() > MAX_SIZE) {
            keep(files, path, before);
            report(notes, label(path) + " is larger than " + size(MAX_SIZE) + " and is not uploaded");
        } else {
            try {
                files.put(path, id(file, attrs, ins));
            } catch (IOException e) {
                keep(files, path, before);
                report(notes, label(path) + " could not be read and is not uploaded: " + e.getMessage());
            }
        }
    }

    private static void keep(Map<String, ObjectId> files, String path, ObjectId before) {
        if (before != null) {
            files.put(path, before);
        }
    }

    /** Once per run of Archi: an automatic sync every few seconds must not repeat it. */
    private void report(List<String> notes, String note) {
        if (REPORTED.add(dir + "|" + note)) {
            notes.add(note);
        }
    }

    private static boolean junk(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return JUNK.contains(lower) || lower.startsWith("~$") || lower.endsWith(TEMP_SUFFIX);
    }

    private ObjectId id(Path file, BasicFileAttributes attrs, ObjectInserter ins) throws IOException {
        Stamp stamp = STAMPS.get(file);
        if (stamp != null && stamp.holds(attrs) && repo.getObjectDatabase().has(stamp.id)) {
            return stamp.id;
        }
        long taken = System.currentTimeMillis();
        ObjectId id = ins.insert(Constants.OBJ_BLOB, Files.readAllBytes(file));
        STAMPS.put(file, new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), taken, id));
        return id;
    }

    /** The content id of a file on disk, null if no regular file is there. */
    private ObjectId onDisk(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter(); InputStream in = Files.newInputStream(file)) {
            return formatter.idFor(Constants.OBJ_BLOB, Files.size(file), in);
        }
    }

    /**
     * Brings the Assets folder from one version's assets to another's. A file the user changed meanwhile (it is not
     * as {@code from} says) is never overwritten or deleted: where a new version arrives, theirs is moved to a
     * conflict name first, and the next sync shares it.
     */
    void apply(Assets from, Assets to, List<String> notes) throws IOException {
        Set<String> paths = new TreeSet<>(from.files().keySet());
        paths.addAll(to.files().keySet());
        for (String path : paths) {
            ObjectId f = from.files().get(path);
            ObjectId t = to.files().get(path);
            if (Objects.equals(f, t)) {
                continue;
            }
            if (!storable(path, paths)) {
                if (t != null) {
                    report(notes, label(path) + " cannot be stored on this computer; it stays shared");
                }
                continue;
            }
            Path file = file(path);
            ObjectId d = onDisk(file);
            if (Objects.equals(d, t)) {
                continue;
            }
            if (t == null) {
                if (Objects.equals(d, f)) {
                    Files.delete(file);
                    STAMPS.remove(file);
                    prune(file.getParent());
                }
                continue;
            }
            if (d != null && !Objects.equals(d, f)) {
                String copy = conflictPath(path, d, paths);
                Files.move(file, file(copy), StandardCopyOption.REPLACE_EXISTING);
                notes.add(label(path) + " changed on this computer and in the shared model: the shared version "
                        + "keeps the name, yours is saved as '" + name(copy) + "'");
            }
            write(file, t);
        }
    }

    /** Puts back the files of a version that are not on disk; nothing is overwritten or deleted. */
    void addMissing(Assets of) throws IOException {
        for (Map.Entry<String, ObjectId> f : of.files().entrySet()) {
            Path file = file(f.getKey());
            if (storable(f.getKey(), of.files().keySet()) && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                write(file, f.getValue());
            }
        }
    }

    private void write(Path file, ObjectId content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try (OutputStream out = Files.newOutputStream(tmp)) {
            repo.open(content, Constants.OBJ_BLOB).copyTo(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        STAMPS.put(file, new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), System.currentTimeMillis(), content.copy()));
    }

    /** Removes the folders a deleted file leaves empty, up to the Assets folder itself. */
    private void prune(Path folder) {
        try {
            for (Path p = folder; p != null && !p.equals(dir) && p.startsWith(dir); p = p.getParent()) {
                try (var content = Files.list(p)) {
                    if (content.findAny().isPresent()) {
                        return;
                    }
                }
                Files.delete(p);
            }
        } catch (IOException e) {
            // an empty folder left behind harms nothing
        }
    }

    /**
     * True if this computer can hold the file where it belongs: a valid name, inside the Assets folder, reached
     * through real folders only (never a link, which could lead anywhere), with no other file of the same name in
     * another case before it (Windows and macOS could hold only one of them).
     */
    boolean storable(String path, Set<String> all) {
        if (!valid(path)) {
            return false;
        }
        for (String other : all) {
            if (other.compareTo(path) < 0 && other.equalsIgnoreCase(path)) {
                return false;
            }
        }
        try {
            Path root = path.startsWith(PREFIX) ? dir : workspace;
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path file = file(path).normalize();
            if (!file.startsWith(root)) {
                return false;
            }
            for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) {
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
            }
            return !Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** A path under Assets/ whose every part is a file name Windows, macOS and Linux all accept, or a shared file. */
    static boolean valid(String path) {
        if (rootFile(path)) {
            return true;
        }
        if (!path.startsWith(PREFIX) || path.length() == PREFIX.length() || path.endsWith("/")) {
            return false;
        }
        for (String part : path.substring(PREFIX.length()).split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.endsWith(".") || part.endsWith(" ") || RESERVED.matcher(part).matches()
                    || part.chars().anyMatch(c -> c < 0x20 || "\\:*?\"<>|".indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the Assets folder and the shared files of a model about to be shared from its folder into a new
     * workspace; links are left out.
     */
    static void copyFrom(Path modelFolder, Path workspace) throws IOException {
        for (String name : ROOT_FILES) {
            if (Files.isRegularFile(modelFolder.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(modelFolder.resolve(name), workspace.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path from = modelFolder.resolve(DIR);
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path to = workspace.resolve(DIR);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path target = to.resolve(from.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ================================================================ describing changes

    /** e.g. "Added asset 'flow.png', changed 2 assets"; null if the files are the same. */
    static String summary(Assets before, Assets after) {
        Map<String, List<String>> byVerb = new LinkedHashMap<>();
        changes(before, after).forEach((path, verb) -> byVerb.computeIfAbsent(verb, k -> new ArrayList<>()).add(path));
        List<String> parts = new ArrayList<>();
        for (String verb : List.of("Added", "Changed", "Deleted")) {
            List<String> names = byVerb.get(verb);
            if (names != null) {
                String text = names.size() == 1 ? label(names.get(0)).toLowerCase(Locale.ROOT).substring(0, 1)
                        + label(names.get(0)).substring(1)
                        : names.size() + (names.stream().allMatch(n -> n.startsWith(PREFIX)) ? " assets" : " shared files");
                parts.add((parts.isEmpty() ? verb : verb.toLowerCase(Locale.ROOT)) + " " + text);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** The changed files as entries of a review, under the heading "Assets". */
    List<ModelDiff.Entry> diff(Assets before, Assets after) throws IOException {
        List<ModelDiff.Entry> entries = new ArrayList<>();
        try (ObjectReader reader = repo.newObjectReader()) {
            for (Map.Entry<String, String> c : changes(before, after).entrySet()) {
                ObjectId b = before.files().get(c.getKey());
                ObjectId a = after.files().get(c.getKey());
                ModelDiff.Kind kind = b == null ? ModelDiff.Kind.ADDED : a == null ? ModelDiff.Kind.DELETED : ModelDiff.Kind.CHANGED;
                List<ModelDiff.Change> details = kind == ModelDiff.Kind.DELETED ? List.of()
                        : List.of(new ModelDiff.Change(kind, "File", b == null ? null : size(reader, b), size(reader, a)));
                entries.add(new ModelDiff.Entry(kind, c.getKey().startsWith(PREFIX) ? "Assets" : "Shared files",
                        name(c.getKey()), details));
            }
        }
        return entries;
    }

    private static SortedMap<String, String> changes(Assets before, Assets after) {
        SortedMap<String, String> changes = new TreeMap<>();
        Set<String> paths = new TreeSet<>(before.files().keySet());
        paths.addAll(after.files().keySet());
        for (String path : paths) {
            ObjectId b = before.files().get(path);
            ObjectId a = after.files().get(path);
            if (!Objects.equals(a, b)) {
                changes.put(path, b == null ? "Added" : a == null ? "Deleted" : "Changed");
            }
        }
        return changes;
    }

    private static String size(ObjectReader reader, ObjectId id) throws IOException {
        return size(reader.getObjectSize(id, Constants.OBJ_BLOB)) + ", content " + id.name().substring(0, 7);
    }

    private static String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** The path as the user knows it: inside the Assets folder, or the name of a file next to the model. */
    static String name(String path) {
        return path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
    }

    /** "Asset 'flow.png'", "File 'documentation.rules'". */
    private static String label(String path) {
        return (path.startsWith(PREFIX) ? "Asset '" : "File '") + name(path) + "'";
    }
}
