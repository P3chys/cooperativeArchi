package io.github.p3chys.cooperativearchi.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import io.github.p3chys.cooperativearchi.xml.XNode;
import io.github.p3chys.cooperativearchi.xml.Xml;

/** An Archi model flattened into {@link ModelNode}s keyed by id, plus the images of an archive model. */
public final class ModelGraph {

    /** Derived by Archi on load; never stored so it cannot cause merge conflicts. */
    static final String DERIVED_ATTR = "targetConnections";
    private static final String ARCHIVE_MODEL_ENTRY = "model.xml";

    private final String rootId;
    private final Map<String, ModelNode> nodes;
    private final Map<String, byte[]> images;

    public ModelGraph(String rootId, Map<String, ModelNode> nodes, Map<String, byte[]> images) {
        this.rootId = rootId;
        this.nodes = nodes;
        this.images = images;
    }

    public String rootId() {
        return rootId;
    }

    public ModelNode root() {
        return nodes.get(rootId);
    }

    public Map<String, ModelNode> nodes() {
        return nodes;
    }

    /** Image path inside the archive (e.g. {@code images/1a2b.png}) to bytes. */
    public Map<String, byte[]> images() {
        return images;
    }

    public ModelNode get(String id) {
        return nodes.get(id);
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    // ---------------------------------------------------------------- .archimate file

    /** Reads a .archimate file, plain XML or zip archive (model with images). */
    public static ModelGraph read(byte[] bytes) throws IOException {
        if (bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') {
            byte[] model = null;
            Map<String, byte[]> images = new TreeMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    if (e.getName().equals(ARCHIVE_MODEL_ENTRY)) {
                        model = zip.readAllBytes();
                    } else {
                        images.put(e.getName(), zip.readAllBytes());
                    }
                }
            }
            if (model == null) {
                throw new IOException("Archive model has no " + ARCHIVE_MODEL_ENTRY);
            }
            return fromXml(Xml.parse(model), images);
        }
        return fromXml(Xml.parse(bytes), new TreeMap<>());
    }

    /** Writes a .archimate file; uses the archive format when the model has images, as Archi does. */
    public byte[] write() throws IOException {
        byte[] xml = Xml.write(toXml());
        if (images.isEmpty()) {
            return xml;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ARCHIVE_MODEL_ENTRY));
            zip.write(xml);
            zip.closeEntry();
            for (Map.Entry<String, byte[]> image : new TreeMap<>(images).entrySet()) {
                zip.putNextEntry(new ZipEntry(image.getKey()));
                zip.write(image.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- XML tree <-> graph

    public static ModelGraph fromXml(XNode root, Map<String, byte[]> images) throws IOException {
        if (root.id() == null) {
            throw new IOException("Model root has no id");
        }
        Map<String, ModelNode> nodes = new LinkedHashMap<>();
        flatten(root, null, nodes);
        return new ModelGraph(root.id(), nodes, images);
    }

    private static void flatten(XNode x, String parentId, Map<String, ModelNode> out) throws IOException {
        String id = x.id();
        Map<String, String> attrs = new LinkedHashMap<>(x.attrs());
        attrs.remove("id");
        attrs.remove(DERIVED_ATTR);
        Map<String, List<XNode>> fields = new LinkedHashMap<>();
        List<String> childIds = new ArrayList<>();
        for (XNode child : x.children()) {
            if (child.id() != null) {
                childIds.add(child.id());
            } else {
                fields.computeIfAbsent(child.tag(), k -> new ArrayList<>()).add(child);
            }
        }
        if (out.containsKey(id)) {
            throw new IOException("Duplicate id in model: " + id);
        }
        out.put(id, new ModelNode(id, x.tag(), parentId, attrs, fields, childIds));
        for (XNode child : x.children()) {
            if (child.id() != null) {
                flatten(child, id, out);
            }
        }
    }

    public XNode toXml() {
        return toXml(rootId);
    }

    /** Rebuilds the XML subtree of one node. */
    public XNode toXml(String id) {
        ModelNode n = nodes.get(id);
        Map<String, String> attrs = new LinkedHashMap<>(n.attrs());
        attrs.put("id", n.id());
        List<XNode> children = new ArrayList<>(n.fieldList());
        for (String childId : n.childIds()) {
            if (nodes.containsKey(childId)) {
                children.add(toXml(childId));
            }
        }
        return new XNode(n.tag(), Xml.canonicalOrder(attrs), "", children);
    }

    // ---------------------------------------------------------------- navigation

    /** The node and all nodes below it. */
    public Set<String> subtree(String id) {
        Set<String> result = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            String current = todo.pop();
            ModelNode n = nodes.get(current);
            if (n != null && result.add(current)) {
                todo.addAll(n.childIds());
            }
        }
        return result;
    }

    /** True if {@code ancestor} is {@code id} or one of its parents. */
    public boolean isWithin(String id, String ancestor) {
        Set<String> seen = new HashSet<>();
        for (String cur = id; cur != null && seen.add(cur); cur = nodes.containsKey(cur) ? nodes.get(cur).parentId() : null) {
            if (cur.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    /** The enclosing view of a diagram object, or null. */
    public ModelNode enclosingView(String id) {
        ModelNode n = nodes.get(id);
        while (n != null) {
            if (n.type().endsWith("DiagramModel") || n.type().equals("SketchModel") || n.type().equals("CanvasModel")) {
                return n;
            }
            n = n.parentId() == null ? null : nodes.get(n.parentId());
        }
        return null;
    }
}
