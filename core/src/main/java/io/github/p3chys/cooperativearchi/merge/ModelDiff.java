package io.github.p3chys.cooperativearchi.merge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.p3chys.cooperativearchi.merge.ChangeSummary.Category;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.ModelNode;
import io.github.p3chys.cooperativearchi.model.SplitLayout;
import io.github.p3chys.cooperativearchi.xml.XNode;

/**
 * Detailed difference between two versions of a model, shown before a pull or push: every added, deleted or changed
 * element, relationship, view, folder and model setting, with each changed name, documentation, property,
 * reference... and its old and new value. Pure layout (positions, sizes, bend points, z-order in views) is only
 * counted, not listed.
 */
public final class ModelDiff {

    public enum Kind {
        ADDED("+"), CHANGED("~"), DELETED("-");

        final String symbol;

        Kind(String symbol) {
            this.symbol = symbol;
        }
    }

    /**
     * One detail of an {@link Entry}. CHANGED: a value with before and after. ADDED: a value of a new object
     * ({@code after}), or with no value an object added to a view. DELETED: an object removed from a view.
     */
    public record Change(Kind kind, String what, String before, String after) {

        @Override
        public String toString() {
            return switch (kind) {
                case ADDED -> after == null ? "+ " + what : what + ": " + oneLine(after);
                case DELETED -> "- " + what;
                case CHANGED -> what + ": " + oneLine(before) + " → " + oneLine(after);
            };
        }
    }

    /** An added, deleted or changed object; {@code category} is the heading it is listed under. */
    public record Entry(Kind kind, String category, String item, List<Change> changes) {

        public Entry {
            changes = List.copyOf(changes);
        }

        @Override
        public String toString() {
            return kind.symbol + " " + item;
        }
    }

    /** Sub-elements that only position things in a view. */
    private static final Set<String> LAYOUT_FIELDS = Set.of("bounds", "bendpoint");
    /** Field tags compared per entry, keyed by the given attribute (as the merge does). */
    private static final Map<String, String> KEYED_FIELDS = Map.of("property", "key", "feature", "name");

    private final List<Entry> entries;
    private final int layoutChanges;

    private ModelDiff(List<Entry> entries, int layoutChanges) {
        this.entries = List.copyOf(entries);
        this.layoutChanges = layoutChanges;
    }

    /** What changes from {@code before} (null = nothing yet, e.g. a first upload) to {@code after}. */
    public static ModelDiff of(ModelGraph before, ModelGraph after) {
        ModelGraph b = before == null ? new ModelGraph(after.rootId(), Map.of(), Map.of()) : before;
        return new Builder(b, after).build();
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Number of objects whose position, size, bend points or z-order changed (not listed in {@link #entries}). */
    public int layoutChanges() {
        return layoutChanges;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && layoutChanges == 0;
    }

    /** e.g. "Positions or sizes changed for 3 objects in views (not listed)", or null. */
    public String layoutNote() {
        if (layoutChanges == 0) {
            return null;
        }
        return "Positions or sizes changed for " + layoutChanges + (layoutChanges == 1 ? " object" : " objects")
                + " in views (not listed)";
    }

    /** The whole difference as indented text, one line per object and per changed detail. */
    public String toText() {
        if (isEmpty()) {
            return "No changes";
        }
        StringBuilder sb = new StringBuilder();
        String heading = null;
        for (Entry e : entries) {
            if (!e.category().equals(heading)) {
                heading = e.category();
                sb.append(heading).append('\n');
            }
            sb.append("  ").append(e).append('\n');
            for (Change c : e.changes()) {
                sb.append("      ").append(c).append('\n');
            }
        }
        if (layoutChanges > 0) {
            sb.append(layoutNote()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String toString() {
        return toText();
    }

    /** A value on one line (line breaks of documentation shown as ↵), "(none)" for null. */
    public static String oneLine(String s) {
        return s == null ? "(none)" : s.replace("\r\n", "\n").replace("\n", " ↵ ");
    }

    // ---------------------------------------------------------------- computation

    private static final class Builder {
        private final ModelGraph before;
        private final ModelGraph after;
        private final List<Entry> entries = new ArrayList<>();
        private int layout;

        Builder(ModelGraph before, ModelGraph after) {
            this.before = before;
            this.after = after;
        }

        ModelDiff build() {
            Set<String> ids = new LinkedHashSet<>(after.nodes().keySet());
            ids.addAll(before.nodes().keySet());
            for (String id : ids) {
                ModelNode o = before.get(id);
                ModelNode n = after.get(id);
                ModelGraph g = n != null ? after : before;
                ModelNode node = n != null ? n : o;
                ModelNode view = g.enclosingView(id);
                if (view != null && !view.id().equals(id)) {
                    continue; // diagram objects are listed under their view
                }
                Category c = ChangeSummary.category(node, g);
                if (o == null) {
                    entries.add(new Entry(Kind.ADDED, heading(c), Describer.describe(n, after), contentOf(n, after)));
                } else if (n == null) {
                    entries.add(new Entry(Kind.DELETED, heading(c), Describer.describe(o, before), List.of()));
                } else {
                    List<Change> changes = compare(o, n, "");
                    if (Describer.isView(n.type())) {
                        changes.addAll(compareViewContent(o, n));
                    }
                    if (!changes.isEmpty()) {
                        entries.add(new Entry(Kind.CHANGED, heading(c), Describer.describe(n, after), changes));
                    }
                }
            }
            entries.sort(Comparator.comparing((Entry e) -> categoryRank(e.category())).thenComparing(Entry::kind)
                    .thenComparing(e -> e.item().toLowerCase(Locale.ROOT)));
            return new ModelDiff(entries, layout);
        }

        /** Details of a new object: its values, and for a view the objects it contains. */
        private List<Change> contentOf(ModelNode n, ModelGraph g) {
            List<Change> list = new ArrayList<>();
            boolean relation = n.type().endsWith("Relationship");
            n.attrs().forEach((key, v) -> {
                // name, type and (for relations) the ends are already in the item label
                if (!ignoredAttr(key) && !key.equals("name") && !key.equals("xsi:type")
                        && !(relation && (key.equals("source") || key.equals("target")))) {
                    list.add(new Change(Kind.ADDED, Describer.label(key), null, attrValue(key, v, g)));
                }
            });
            n.fields().forEach((tag, values) -> {
                if (LAYOUT_FIELDS.contains(tag)) {
                    return;
                }
                String keyAttr = KEYED_FIELDS.get(tag);
                if (keyAttr != null) {
                    for (XNode x : values) {
                        list.add(new Change(Kind.ADDED, Describer.label(tag + ":" + x.attr(keyAttr)), null,
                                Describer.value(x.attr("value"))));
                    }
                } else {
                    list.add(new Change(Kind.ADDED, Describer.label(tag), null, Describer.fields(values)));
                }
            });
            if (Describer.isView(n.type())) {
                for (String id : below(n.id(), g)) {
                    list.add(new Change(Kind.ADDED, Describer.describeInView(g.get(id), g), null, null));
                }
            }
            return list;
        }

        /** Changed values of an object present in both versions; counts it once if its layout changed. */
        private List<Change> compare(ModelNode o, ModelNode n, String prefix) {
            List<Change> list = new ArrayList<>();
            boolean layoutChanged = false;
            if (!Objects.equals(o.parentId(), n.parentId()) && o.parentId() != null && n.parentId() != null) {
                list.add(new Change(Kind.CHANGED, prefix + Describer.label("parent"),
                        Describer.describeInView(before.get(o.parentId()), before),
                        Describer.describeInView(after.get(n.parentId()), after)));
            }
            Set<String> keys = new LinkedHashSet<>(o.attrs().keySet());
            keys.addAll(n.attrs().keySet());
            for (String key : keys) {
                String ov = o.attrs().get(key);
                String nv = n.attrs().get(key);
                if (!ignoredAttr(key) && !Objects.equals(ov, nv)) {
                    list.add(new Change(Kind.CHANGED, prefix + Describer.label(key), attrValue(key, ov, before),
                            attrValue(key, nv, after)));
                }
            }
            Set<String> tags = new LinkedHashSet<>(o.fields().keySet());
            tags.addAll(n.fields().keySet());
            for (String tag : tags) {
                List<XNode> ol = o.fields().getOrDefault(tag, List.of());
                List<XNode> nl = n.fields().getOrDefault(tag, List.of());
                if (ol.equals(nl)) {
                    continue;
                }
                String keyAttr = KEYED_FIELDS.get(tag);
                if (LAYOUT_FIELDS.contains(tag)) {
                    layoutChanged = true;
                } else if (keyAttr != null) {
                    list.addAll(compareKeyed(prefix, tag, keyAttr, ol, nl));
                } else {
                    list.add(new Change(Kind.CHANGED, prefix + Describer.label(tag), Describer.fields(ol),
                            Describer.fields(nl)));
                }
            }
            // order matters only inside views (z-order); folder contents are sorted by Archi
            if (!SplitLayout.isFolder(n) && n.parentId() != null && reordered(o.childIds(), n.childIds())) {
                layoutChanged = true;
            }
            if (layoutChanged) {
                layout++;
            }
            return list;
        }

        private static List<Change> compareKeyed(String prefix, String tag, String keyAttr, List<XNode> ol,
                List<XNode> nl) {
            Map<String, XNode> om = byKey(ol, keyAttr);
            Map<String, XNode> nm = byKey(nl, keyAttr);
            Set<String> keys = new LinkedHashSet<>(om.keySet());
            keys.addAll(nm.keySet());
            List<Change> list = new ArrayList<>();
            for (String k : keys) {
                XNode ov = om.get(k);
                XNode nv = nm.get(k);
                if (!Objects.equals(ov, nv)) {
                    list.add(new Change(Kind.CHANGED, prefix + Describer.label(tag + ":" + k),
                            ov == null ? "(none)" : Describer.value(ov.attr("value")),
                            nv == null ? "(none)" : Describer.value(nv.attr("value"))));
                }
            }
            return list;
        }

        /** Objects added to, removed from or changed inside a view present in both versions. */
        private List<Change> compareViewContent(ModelNode o, ModelNode n) {
            Set<String> ids = new LinkedHashSet<>(below(n.id(), after));
            ids.addAll(below(o.id(), before));
            List<Change> list = new ArrayList<>();
            for (String id : ids) {
                ModelNode bo = before.get(id);
                ModelNode ao = after.get(id);
                if (bo == null) {
                    list.add(new Change(Kind.ADDED, Describer.describeInView(ao, after), null, null));
                } else if (ao == null) {
                    list.add(new Change(Kind.DELETED, Describer.describeInView(bo, before), null, null));
                } else {
                    list.addAll(compare(bo, ao, Describer.describeInView(ao, after) + " › "));
                }
            }
            return list;
        }

        /** The objects inside a view, depth first in z-order. */
        private static List<String> below(String viewId, ModelGraph g) {
            List<String> out = new ArrayList<>();
            Deque<String> todo = new ArrayDeque<>(g.get(viewId).childIds());
            Set<String> seen = new LinkedHashSet<>();
            while (!todo.isEmpty()) {
                String id = todo.pollFirst();
                ModelNode x = g.get(id);
                if (x == null || !seen.add(id)) {
                    continue;
                }
                out.add(id);
                List<String> kids = x.childIds();
                for (int i = kids.size() - 1; i >= 0; i--) {
                    todo.addFirst(kids.get(i));
                }
            }
            return out;
        }

        /** True if the objects present in both lists are in a different order (additions are listed separately). */
        private static boolean reordered(List<String> a, List<String> b) {
            return !a.stream().filter(b::contains).toList().equals(b.stream().filter(a::contains).toList());
        }

        private static boolean ignoredAttr(String key) {
            return key.startsWith("xmlns");
        }

        /** An attribute value for display: references as names, the type humanized. */
        private static String attrValue(String key, String v, ModelGraph g) {
            if (v == null) {
                return "(none)";
            }
            if (key.equals("xsi:type")) {
                return Describer.humanize(v.substring(v.indexOf(':') + 1));
            }
            if (!key.equals("name") && !v.isBlank()) {
                String[] tokens = v.trim().split("\\s+");
                if (Arrays.stream(tokens).allMatch(g::contains)) {
                    return Arrays.stream(tokens).map(t -> "'" + Describer.name(t, g) + "'").collect(Collectors.joining(", "));
                }
            }
            return Describer.value(v);
        }

        private static Map<String, XNode> byKey(List<XNode> list, String keyAttr) {
            Map<String, XNode> m = new LinkedHashMap<>();
            for (XNode x : list) {
                m.put(String.valueOf(x.attr(keyAttr)), x);
            }
            return m;
        }
    }

    private static String heading(Category c) {
        return switch (c) {
            case ELEMENT -> "Elements";
            case RELATION -> "Relationships";
            case VIEW -> "Views";
            case FOLDER -> "Folders";
            case MODEL -> "Model settings";
        };
    }

    private static int categoryRank(String heading) {
        for (Category c : Category.values()) {
            if (heading(c).equals(heading)) {
                return c.ordinal();
            }
        }
        return Category.values().length;
    }
}
