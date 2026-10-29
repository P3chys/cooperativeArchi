package io.github.p3chys.cooperativearchi.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.p3chys.cooperativearchi.merge.Conflict.Kind;
import io.github.p3chys.cooperativearchi.merge.Conflict.Side;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.ModelNode;
import io.github.p3chys.cooperativearchi.model.SplitLayout;
import io.github.p3chys.cooperativearchi.xml.XNode;

/**
 * Three-way merge of Archi models at the level of single objects and their single attributes.
 *
 * <p>Changes to different objects, or to different attributes/properties of the same object, merge
 * silently. Only the same attribute changed differently on both sides, or a deletion on one side of
 * something changed on the other, become {@link Conflict}s. After resolution the result is made
 * referentially consistent: an object still referenced by the merged model is restored instead of
 * leaving a dangling reference (Archi would refuse to open such a model).
 *
 * <pre>
 * MergeSession s = ModelMerger.prepare(base, mine, theirs);
 * s.conflicts().forEach(c -> c.choose(...));
 * ModelGraph merged = s.finish();
 * </pre>
 */
public final class ModelMerger {

    /** Field tags merged per entry, keyed by the given attribute, instead of as one list. */
    private static final Map<String, String> KEYED_FIELDS = Map.of("property", "key", "feature", "name");
    private static final Pattern IMAGE_REF = Pattern.compile("images/[^\"\\s]+");

    private ModelMerger() {
    }

    public static MergeSession prepare(ModelGraph base, ModelGraph mine, ModelGraph theirs) {
        if (!mine.rootId().equals(theirs.rootId())) {
            throw new IllegalArgumentException("Cannot merge two different models");
        }
        return new MergeSession(base, mine, theirs);
    }

    /** Merges without user interaction; throws if there are conflicts. */
    public static ModelGraph mergeClean(ModelGraph base, ModelGraph mine, ModelGraph theirs) {
        MergeSession s = prepare(base, mine, theirs);
        if (!s.conflicts().isEmpty()) {
            throw new IllegalStateException("Unexpected conflicts: " + s.conflicts());
        }
        return s.finish();
    }

    public static final class MergeSession {
        private final ModelGraph base;
        private final ModelGraph mine;
        private final ModelGraph theirs;
        private final Map<String, ModelNode> result = new LinkedHashMap<>();
        private final List<Conflict> conflicts = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final Set<String> allIds = new HashSet<>();
        private ModelGraph merged;

        private MergeSession(ModelGraph base, ModelGraph mine, ModelGraph theirs) {
            this.base = base;
            this.mine = mine;
            this.theirs = theirs;
            allIds.addAll(base.nodes().keySet());
            allIds.addAll(mine.nodes().keySet());
            allIds.addAll(theirs.nodes().keySet());
            compute();
        }

        public List<Conflict> conflicts() {
            return conflicts;
        }

        /** Automatic decisions taken while merging (e.g. restored elements), for the sync report. */
        public List<String> notes() {
            return notes;
        }

        public ModelGraph mine() {
            return mine;
        }

        public ModelGraph theirs() {
            return theirs;
        }

        // ------------------------------------------------------------ phase 1: tentative merge

        private void compute() {
            Set<String> dirtyMine = dirty(mine);
            Set<String> dirtyTheirs = dirty(theirs);
            Set<String> ids = new LinkedHashSet<>(mine.nodes().keySet());
            ids.addAll(theirs.nodes().keySet());

            for (String id : ids) {
                ModelNode b = base.get(id);
                ModelNode o = mine.get(id);
                ModelNode t = theirs.get(id);
                if (o != null && t != null) {
                    result.put(id, mergeNode(b, o, t));
                } else if (b == null) {
                    result.put(id, o != null ? o : t);
                } else if (o == null) {
                    // deleted by me
                    if (dirtyTheirs.contains(id)) {
                        result.put(id, t);
                        if (!isTopmost(id, theirs, dirtyTheirs, mine)) {
                            continue;
                        }
                        conflicts.add(new Conflict(id, Kind.DELETED_BY_ME, "deleted", Describer.describe(t, theirs),
                                "Deleted / changed", "Deleted", "Changed", null, null, null));
                    }
                } else {
                    // deleted by them
                    if (dirtyMine.contains(id)) {
                        result.put(id, o);
                        if (!isTopmost(id, mine, dirtyMine, theirs)) {
                            continue;
                        }
                        conflicts.add(new Conflict(id, Kind.DELETED_BY_THEM, "deleted", Describer.describe(o, mine),
                                "Deleted / changed", "Changed", "Deleted", null, null, null));
                    }
                }
            }
        }

        /** Only the outermost deleted-but-changed object gets a conflict; its content follows the decision. */
        private boolean isTopmost(String id, ModelGraph keeper, Set<String> dirtyKeeper, ModelGraph deleter) {
            String parent = keeper.get(id).parentId();
            return parent == null || deleter.contains(parent) || !base.contains(parent) || !dirtyKeeper.contains(parent);
        }

        /** Ids of objects changed in {@code side} relative to base, plus all their ancestors. */
        private Set<String> dirty(ModelGraph side) {
            Set<String> dirty = new HashSet<>();
            for (ModelNode n : side.nodes().values()) {
                ModelNode b = base.get(n.id());
                boolean changed = b == null || !n.sameContent(b)
                        || (!SplitLayout.isFolder(n) && !n.id().equals(side.rootId()) && !n.childIds().equals(b.childIds()));
                if (changed) {
                    for (String cur = n.id(); cur != null && dirty.add(cur);) {
                        ModelNode c = side.get(cur);
                        cur = c == null ? null : c.parentId();
                    }
                }
            }
            return dirty;
        }

        private ModelNode mergeNode(ModelNode b, ModelNode o, ModelNode t) {
            if (o.equals(t) || (b != null && t.equals(b))) {
                return o;
            }
            if (b != null && o.equals(b)) {
                return t;
            }
            String item = Describer.describe(o, mine);
            String id = o.id();

            // parent
            String parent = o.parentId();
            String bp = b == null ? null : b.parentId();
            if (!Objects.equals(o.parentId(), t.parentId())) {
                if (Objects.equals(o.parentId(), bp)) {
                    parent = t.parentId();
                } else if (!Objects.equals(t.parentId(), bp)) {
                    conflicts.add(new Conflict(id, Kind.PARENT, "parent", item, Describer.label("parent"),
                            Describer.describe(mine.get(o.parentId()), mine),
                            Describer.describe(theirs.get(t.parentId()), theirs), t.parentId(), null, null));
                }
            }

            // attributes
            Map<String, String> bAttrs = b == null ? Map.of() : b.attrs();
            Map<String, String> attrs = new LinkedHashMap<>(o.attrs());
            Set<String> keys = new LinkedHashSet<>(o.attrs().keySet());
            keys.addAll(t.attrs().keySet());
            for (String key : keys) {
                String bv = bAttrs.get(key);
                String ov = o.attrs().get(key);
                String tv = t.attrs().get(key);
                if (Objects.equals(ov, tv) || Objects.equals(tv, bv)) {
                    continue;
                }
                if (Objects.equals(ov, bv)) {
                    put(attrs, key, tv);
                } else if (key.equals("version") && id.equals(mine.rootId())) {
                    put(attrs, key, compareVersions(ov, tv) >= 0 ? ov : tv);
                } else {
                    conflicts.add(new Conflict(id, Kind.ATTRIBUTE, key, item, Describer.label(key), Describer.value(ov),
                            Describer.value(tv), tv, null, null));
                }
            }

            // fields
            Map<String, List<XNode>> bFields = b == null ? Map.of() : b.fields();
            Map<String, List<XNode>> fields = new LinkedHashMap<>(o.fields());
            Set<String> tags = new LinkedHashSet<>(o.fields().keySet());
            tags.addAll(t.fields().keySet());
            for (String tag : tags) {
                List<XNode> bl = bFields.getOrDefault(tag, List.of());
                List<XNode> ol = o.fields().getOrDefault(tag, List.of());
                List<XNode> tl = t.fields().getOrDefault(tag, List.of());
                if (ol.equals(tl) || tl.equals(bl)) {
                    continue;
                }
                if (ol.equals(bl)) {
                    fields.put(tag, tl);
                    continue;
                }
                String keyAttr = KEYED_FIELDS.get(tag);
                if (keyAttr != null && uniqueKeys(bl, keyAttr) && uniqueKeys(ol, keyAttr) && uniqueKeys(tl, keyAttr)) {
                    fields.put(tag, mergeKeyed(id, item, tag, keyAttr, bl, ol, tl));
                } else {
                    conflicts.add(new Conflict(id, Kind.FIELD, tag, item, Describer.label(tag), Describer.fields(ol),
                            Describer.fields(tl), tl, null, null));
                }
            }
            return new ModelNode(id, o.tag(), parent, attrs, fields, o.childIds());
        }

        private List<XNode> mergeKeyed(String id, String item, String tag, String keyAttr, List<XNode> bl,
                List<XNode> ol, List<XNode> tl) {
            Map<String, XNode> bm = byKey(bl, keyAttr);
            Map<String, XNode> om = byKey(ol, keyAttr);
            Map<String, XNode> tm = byKey(tl, keyAttr);
            Set<String> keys = new LinkedHashSet<>(om.keySet());
            keys.addAll(tm.keySet());
            List<XNode> out = new ArrayList<>();
            for (String k : keys) {
                XNode bv = bm.get(k);
                XNode ov = om.get(k);
                XNode tv = tm.get(k);
                XNode chosen;
                if (Objects.equals(ov, tv) || Objects.equals(tv, bv)) {
                    chosen = ov;
                } else if (Objects.equals(ov, bv)) {
                    chosen = tv;
                } else {
                    chosen = ov;
                    conflicts.add(new Conflict(id, Kind.FIELD, tag + ":" + k, item, Describer.label(tag + ":" + k),
                            ov == null ? "(removed)" : Describer.value(ov.attrs().get("value")),
                            tv == null ? "(removed)" : Describer.value(tv.attrs().get("value")), null, keyAttr, tv));
                }
                if (chosen != null) {
                    out.add(chosen);
                }
            }
            return out;
        }

        // ------------------------------------------------------------ phase 2: apply decisions

        public ModelGraph finish() {
            if (merged != null) {
                return merged;
            }
            Set<String> explicitlyDeleted = new HashSet<>();
            for (Conflict c : conflicts) {
                if (c.choice() == Side.THEIRS) {
                    applyTheirs(c, explicitlyDeleted);
                } else if (c.kind() == Kind.DELETED_BY_ME) {
                    delete(c.nodeId(), theirs, explicitlyDeleted);
                }
            }
            for (Conflict c : conflicts) {
                if (c.kind() == Kind.DELETED_BY_ME && c.choice() == Side.THEIRS) {
                    restoreSubtree(c.nodeId(), theirs, explicitlyDeleted);
                } else if (c.kind() == Kind.DELETED_BY_THEM && c.choice() == Side.MINE) {
                    restoreSubtree(c.nodeId(), mine, explicitlyDeleted);
                }
            }
            makeConsistent(explicitlyDeleted);
            rebuildChildren();

            Map<String, byte[]> images = new TreeMap<>(theirs.images());
            images.putAll(mine.images());
            Set<String> used = new HashSet<>();
            for (ModelNode n : result.values()) {
                for (String v : n.attrs().values()) {
                    Matcher m = IMAGE_REF.matcher(v);
                    while (m.find()) {
                        used.add(m.group());
                    }
                }
            }
            images.keySet().retainAll(used);
            merged = SplitLayout.canonical(new ModelGraph(mine.rootId(), result, images));
            return merged;
        }

        private void applyTheirs(Conflict c, Set<String> explicitlyDeleted) {
            ModelNode n = result.get(c.nodeId());
            if (n == null) {
                return;
            }
            switch (c.kind()) {
                case PARENT -> result.put(n.id(), n.withParent((String) c.theirsValue));
                case ATTRIBUTE -> {
                    Map<String, String> attrs = new LinkedHashMap<>(n.attrs());
                    put(attrs, c.key(), (String) c.theirsValue);
                    result.put(n.id(), n.withAttrs(attrs));
                }
                case FIELD -> {
                    Map<String, List<XNode>> fields = new LinkedHashMap<>(n.fields());
                    if (c.fieldKeyAttr == null) {
                        @SuppressWarnings("unchecked")
                        List<XNode> value = (List<XNode>) c.theirsValue;
                        fields.put(c.key(), value);
                    } else {
                        String tag = c.key().substring(0, c.key().indexOf(':'));
                        String k = c.key().substring(tag.length() + 1);
                        List<XNode> list = new ArrayList<>(fields.getOrDefault(tag, List.of()));
                        int idx = indexOfKey(list, c.fieldKeyAttr, k);
                        if (c.theirsField == null) {
                            if (idx >= 0) {
                                list.remove(idx);
                            }
                        } else if (idx >= 0) {
                            list.set(idx, c.theirsField);
                        } else {
                            list.add(c.theirsField);
                        }
                        fields.put(tag, list);
                    }
                    result.put(n.id(), n.withFields(fields));
                }
                case DELETED_BY_THEM -> delete(c.nodeId(), mine, explicitlyDeleted);
                case DELETED_BY_ME -> {
                    // keep: restored in finish()
                }
            }
        }

        private void delete(String id, ModelGraph keeper, Set<String> explicitlyDeleted) {
            Set<String> ids = new HashSet<>(keeper.subtree(id));
            ids.addAll(base.subtree(id));
            ids.add(id);
            explicitlyDeleted.addAll(ids);
            result.keySet().removeAll(ids);
        }

        private void restoreSubtree(String id, ModelGraph from, Set<String> explicitlyDeleted) {
            for (String sub : from.subtree(id)) {
                if (!result.containsKey(sub) && !explicitlyDeleted.contains(sub)) {
                    result.put(sub, from.get(sub));
                }
            }
        }

        /** Restores what is still referenced; removes what references something explicitly deleted. */
        private void makeConsistent(Set<String> explicitlyDeleted) {
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < 1000) {
                changed = false;
                for (ModelNode n : List.copyOf(result.values())) {
                    if (!result.containsKey(n.id())) {
                        continue;
                    }
                    List<String> needed = new ArrayList<>();
                    if (n.parentId() != null) {
                        needed.add(n.parentId());
                    }
                    needed.addAll(references(n));
                    for (String ref : needed) {
                        if (result.containsKey(ref)) {
                            continue;
                        }
                        changed = true;
                        if (explicitlyDeleted.contains(ref) || source(ref) == null) {
                            result.remove(n.id());
                            explicitlyDeleted.add(n.id());
                            break;
                        }
                        ModelGraph src = source(ref);
                        notes.add("Restored " + Describer.describe(src.get(ref), src) + " because "
                                + Describer.describe(n, graphOf(n.id())) + " still uses it");
                        restoreSubtree(ref, src, explicitlyDeleted);
                        result.putIfAbsent(ref, src.get(ref));
                    }
                }
            }
        }

        private ModelGraph source(String id) {
            return mine.contains(id) ? mine : theirs.contains(id) ? theirs : base.contains(id) ? base : null;
        }

        private ModelGraph graphOf(String id) {
            return mine.contains(id) ? mine : theirs;
        }

        /** Ids referenced by attribute values (source, target, archimateElement, model, profiles...). */
        private List<String> references(ModelNode n) {
            List<String> refs = new ArrayList<>();
            for (Map.Entry<String, String> e : n.attrs().entrySet()) {
                String v = e.getValue();
                if (v.isEmpty() || e.getKey().equals("name") || e.getKey().startsWith("xmlns")) {
                    continue;
                }
                String[] tokens = v.trim().split("\\s+");
                boolean isReference = true;
                for (String tok : tokens) {
                    isReference &= allIds.contains(tok);
                }
                if (isReference) {
                    refs.addAll(List.of(tokens));
                }
            }
            return refs;
        }

        /** Child order: keep mine, unless only they reordered; insert their additions near their neighbours. */
        private void rebuildChildren() {
            Map<String, List<String>> byParent = new HashMap<>();
            for (ModelNode n : result.values()) {
                if (n.parentId() != null) {
                    byParent.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n.id());
                }
            }
            for (ModelNode n : List.copyOf(result.values())) {
                List<String> actual = byParent.getOrDefault(n.id(), List.of());
                List<String> o = childIdsOf(mine, n.id());
                List<String> t = childIdsOf(theirs, n.id());
                List<String> b = childIdsOf(base, n.id());
                result.put(n.id(), n.withChildIds(mergeOrder(b, o, t, new LinkedHashSet<>(actual))));
            }
        }

        private static List<String> childIdsOf(ModelGraph g, String id) {
            ModelNode n = g.get(id);
            return n == null ? List.of() : n.childIds();
        }
    }

    static List<String> mergeOrder(List<String> b, List<String> o, List<String> t, Set<String> present) {
        Set<String> common = new HashSet<>(b);
        common.retainAll(o);
        common.retainAll(t);
        List<String> bRel = b.stream().filter(common::contains).toList();
        List<String> oRel = o.stream().filter(common::contains).toList();
        List<String> tRel = t.stream().filter(common::contains).toList();
        boolean useTheirs = oRel.equals(bRel) && !tRel.equals(bRel);
        List<String> skeleton = useTheirs ? t : o;
        List<String> other = useTheirs ? o : t;

        List<String> out = new ArrayList<>(skeleton.stream().filter(present::contains).toList());
        for (int i = 0; i < other.size(); i++) {
            String id = other.get(i);
            if (!present.contains(id) || out.contains(id)) {
                continue;
            }
            // place it just below the next object it was below on the other side, else on top
            int insertAt = out.size();
            for (int j = i + 1; j < other.size(); j++) {
                int pos = out.indexOf(other.get(j));
                if (pos >= 0) {
                    insertAt = pos;
                    break;
                }
            }
            out.add(insertAt, id);
        }
        for (String id : present) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static void put(Map<String, String> attrs, String key, String value) {
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
    }

    private static boolean uniqueKeys(List<XNode> list, String keyAttr) {
        Set<String> seen = new HashSet<>();
        for (XNode x : list) {
            if (!seen.add(String.valueOf(x.attrs().get(keyAttr)))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, XNode> byKey(List<XNode> list, String keyAttr) {
        Map<String, XNode> m = new LinkedHashMap<>();
        for (XNode x : list) {
            m.put(String.valueOf(x.attrs().get(keyAttr)), x);
        }
        return m;
    }

    private static int indexOfKey(List<XNode> list, String keyAttr, String key) {
        for (int i = 0; i < list.size(); i++) {
            if (key.equals(String.valueOf(list.get(i).attrs().get(keyAttr)))) {
                return i;
            }
        }
        return -1;
    }

    /** Compares Archi model versions ("4.9.0" &lt; "5.0.0"); null is the lowest. */
    public static int compareVersions(String a, String b) {
        String[] x = a == null ? new String[0] : a.split("\\.");
        String[] y = b == null ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
