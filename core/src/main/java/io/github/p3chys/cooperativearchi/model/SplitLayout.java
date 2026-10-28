package io.github.p3chys.cooperativearchi.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.p3chys.cooperativearchi.xml.XNode;
import io.github.p3chys.cooperativearchi.xml.Xml;

/**
 * Repository layout: one small XML file per element / relationship / view, folders as directories.
 *
 * <pre>
 * model/model.xml                      model root (name, purpose, properties, profiles)
 * model/business/folder.xml            top-level folder, directory named after its type
 * model/business/id-123.xml            an element
 * model/business/id-456/folder.xml     a user sub-folder, directory named after its id
 * model/diagrams/id-789.xml            a view including all of its diagram objects
 * images/...                           images of the model archive
 * model.archimate                      full model snapshot, openable without the plugin
 * </pre>
 */
public final class SplitLayout {

    public static final String MODEL_DIR = "model/";
    public static final String ROOT_FILE = MODEL_DIR + "model.xml";
    public static final String FOLDER_FILE = "folder.xml";
    public static final String IMAGES_DIR = "images/";
    public static final String SNAPSHOT_FILE = "model.archimate";

    private static final List<String> FOLDER_TYPES = List.of("strategy", "business", "application", "technology",
            "motivation", "implementation_migration", "other", "relations", "diagrams");

    private SplitLayout() {
    }

    /** Files representing the model (including the snapshot), keyed by repository path. */
    public static Map<String, byte[]> write(ModelGraph graph) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        ModelNode root = graph.root();
        Map<String, String> ns = namespaces(root);

        List<XNode> rootChildren = new ArrayList<>(fieldsOf(root));
        for (String childId : root.childIds()) {
            ModelNode child = graph.get(childId);
            if (child == null) {
                continue;
            }
            if (isFolder(child)) {
                String dir = MODEL_DIR + topFolderDir(child, root, graph);
                writeFolder(graph, child, dir, ns, files);
            } else {
                rootChildren.add(graph.toXml(childId));
            }
        }
        Map<String, String> rootAttrs = new LinkedHashMap<>(root.attrs());
        rootAttrs.put("id", root.id());
        files.put(ROOT_FILE, Xml.write(new XNode(root.tag(), rootAttrs, "", rootChildren)));

        graph.images().forEach((path, bytes) -> files.put(path, bytes));
        files.put(SNAPSHOT_FILE, canonical(graph).write());
        return files;
    }

    private static void writeFolder(ModelGraph graph, ModelNode folder, String dir, Map<String, String> ns,
            Map<String, byte[]> files) {
        Map<String, String> attrs = new LinkedHashMap<>(ns);
        attrs.putAll(folder.attrs());
        attrs.put("id", folder.id());
        files.put(dir + "/" + FOLDER_FILE, Xml.write(new XNode(folder.tag(), attrs, "", fieldsOf(folder))));
        for (String childId : folder.childIds()) {
            ModelNode child = graph.get(childId);
            if (child == null) {
                continue;
            }
            if (isFolder(child)) {
                writeFolder(graph, child, dir + "/" + child.id(), ns, files);
            } else {
                XNode x = graph.toXml(childId);
                Map<String, String> withNs = new LinkedHashMap<>(ns);
                withNs.putAll(x.attrs());
                files.put(dir + "/" + child.id() + ".xml", Xml.write(x.withAttrs(withNs)));
            }
        }
    }

    /** Reads the files written by {@link #write}; the snapshot file is ignored. */
    public static ModelGraph read(Map<String, byte[]> files) throws IOException {
        byte[] rootBytes = files.get(ROOT_FILE);
        if (rootBytes == null) {
            throw new IOException("Not a cooperativeArchi repository: " + ROOT_FILE + " is missing");
        }
        Map<String, ModelNode> nodes = new LinkedHashMap<>();
        Map<String, byte[]> images = new TreeMap<>();
        XNode rootX = Xml.parse(rootBytes);
        ModelGraph rootOnly = ModelGraph.fromXml(rootX, Map.of());
        nodes.putAll(rootOnly.nodes());
        String rootId = rootOnly.rootId();

        // Pass 1: folders (directory -> folder id)
        Map<String, String> dirToFolder = new HashMap<>();
        dirToFolder.put(MODEL_DIR.substring(0, MODEL_DIR.length() - 1), rootId);
        List<String> folderFiles = files.keySet().stream()
                .filter(p -> p.startsWith(MODEL_DIR) && p.endsWith("/" + FOLDER_FILE))
                .sorted(Comparator.comparingInt((String p) -> p.split("/").length).thenComparing(p -> p))
                .toList();
        for (String path : folderFiles) {
            String dir = path.substring(0, path.length() - FOLDER_FILE.length() - 1);
            XNode x = stripNamespaces(Xml.parse(files.get(path)));
            String parentFolder = dirToFolder.get(parentDir(dir));
            if (parentFolder == null) {
                throw new IOException("Folder without parent folder: " + path);
            }
            dirToFolder.put(dir, x.id());
            addSubtree(x, parentFolder, nodes, path);
        }
        // Pass 2: elements, relationships, views
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey();
            if (path.startsWith(IMAGES_DIR)) {
                images.put(path, e.getValue());
                continue;
            }
            if (!path.startsWith(MODEL_DIR) || !path.endsWith(".xml") || path.equals(ROOT_FILE)
                    || path.endsWith("/" + FOLDER_FILE)) {
                continue;
            }
            String folderId = dirToFolder.get(parentDir(path));
            if (folderId == null) {
                throw new IOException("File outside of a folder: " + path);
            }
            addSubtree(stripNamespaces(Xml.parse(e.getValue())), folderId, nodes, path);
        }
        return canonical(new ModelGraph(rootId, nodes, images));
    }

    private static void addSubtree(XNode x, String parentId, Map<String, ModelNode> nodes, String path)
            throws IOException {
        ModelGraph sub = ModelGraph.fromXml(x, Map.of());
        for (ModelNode n : sub.nodes().values()) {
            if (nodes.containsKey(n.id())) {
                throw new IOException("Duplicate id " + n.id() + " in " + path);
            }
            nodes.put(n.id(), n.id().equals(sub.rootId()) ? n.withParent(parentId) : n);
        }
    }

    /**
     * Rebuilds the child lists of the model root and of folders from parent pointers in a stable order.
     * Order inside folders has no meaning in Archi (the model tree is sorted); order inside views does
     * (z-order) and is kept.
     */
    public static ModelGraph canonical(ModelGraph graph) {
        Map<String, List<String>> folderChildren = new HashMap<>();
        for (ModelNode n : graph.nodes().values()) {
            ModelNode parent = n.parentId() == null ? null : graph.get(n.parentId());
            if (parent != null && (isFolder(parent) || parent.id().equals(graph.rootId()))) {
                folderChildren.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(n.id());
            }
        }
        Map<String, ModelNode> result = new LinkedHashMap<>();
        for (ModelNode n : graph.nodes().values()) {
            if (isFolder(n) || n.id().equals(graph.rootId())) {
                List<String> kids = new ArrayList<>(folderChildren.getOrDefault(n.id(), List.of()));
                kids.sort(Comparator.comparing((String id) -> folderRank(graph.get(id))).thenComparing(id -> id));
                n = n.withChildIds(kids);
            } else {
                n = n.withChildIds(n.childIds().stream().filter(graph::contains).toList());
            }
            result.put(n.id(), n);
        }
        return new ModelGraph(graph.rootId(), result, graph.images());
    }

    private static int folderRank(ModelNode n) {
        if (!isFolder(n)) {
            return FOLDER_TYPES.size() + 2;
        }
        int i = FOLDER_TYPES.indexOf(n.attrs().getOrDefault("type", ""));
        return i < 0 ? FOLDER_TYPES.size() + 1 : i;
    }

    public static boolean isFolder(ModelNode n) {
        return n != null && n.tag().equals("folder");
    }

    private static String topFolderDir(ModelNode folder, ModelNode root, ModelGraph graph) {
        String type = folder.attrs().get("type");
        if (type == null || !type.matches("[a-z_]+")) {
            return folder.id();
        }
        long sameType = root.childIds().stream().map(graph::get)
                .filter(n -> n != null && isFolder(n) && type.equals(n.attrs().get("type"))).count();
        return sameType == 1 ? type : folder.id();
    }

    private static List<XNode> fieldsOf(ModelNode n) {
        List<XNode> list = new ArrayList<>();
        n.fields().values().forEach(list::addAll);
        return list;
    }

    private static Map<String, String> namespaces(ModelNode root) {
        Map<String, String> ns = new LinkedHashMap<>();
        root.attrs().forEach((k, v) -> {
            if (k.startsWith("xmlns")) {
                ns.put(k, v);
            }
        });
        return ns;
    }

    private static XNode stripNamespaces(XNode x) {
        Map<String, String> attrs = new LinkedHashMap<>();
        x.attrs().forEach((k, v) -> {
            if (!k.startsWith("xmlns")) {
                attrs.put(k, v);
            }
        });
        return x.withAttrs(attrs);
    }

    private static String parentDir(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
