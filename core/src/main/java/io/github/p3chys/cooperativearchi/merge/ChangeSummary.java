package io.github.p3chys.cooperativearchi.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.ModelNode;

/** Short human readable description of the difference between two model versions, used as commit message. */
public final class ChangeSummary {

    private enum Category {
        ELEMENT("element"), RELATION("relationship"), VIEW("view"), FOLDER("folder"), MODEL("model setting");

        final String noun;

        Category(String noun) {
            this.noun = noun;
        }
    }

    private final Map<String, Set<String>> buckets = new LinkedHashMap<>();
    private int total;

    private ChangeSummary() {
    }

    public static ChangeSummary of(ModelGraph before, ModelGraph after) {
        ChangeSummary s = new ChangeSummary();
        Set<String> ids = new LinkedHashSet<>(after.nodes().keySet());
        if (before != null) {
            ids.addAll(before.nodes().keySet());
        }
        Set<String> changedViews = new LinkedHashSet<>();
        for (String id : ids) {
            ModelNode b = before == null ? null : before.get(id);
            ModelNode a = after.get(id);
            if (a != null && b != null && a.sameContent(b) && (isFolderLike(a, after) || a.childIds().equals(b.childIds()))) {
                continue;
            }
            ModelGraph g = a != null ? after : before;
            ModelNode n = a != null ? a : b;
            ModelNode view = g.enclosingView(id);
            if (view != null && !view.id().equals(id)) {
                if (after.contains(view.id()) && before != null && before.contains(view.id())) {
                    changedViews.add(view.id());
                }
                continue;
            }
            String verb = b == null ? "Added" : a == null ? "Deleted" : "Changed";
            s.add(verb, category(n, g), Describer.describe(n, g));
        }
        for (String viewId : changedViews) {
            s.add("Changed", Category.VIEW, Describer.describe(after.get(viewId), after));
        }
        return s;
    }

    private static boolean isFolderLike(ModelNode n, ModelGraph g) {
        return n.tag().equals("folder") || n.id().equals(g.rootId());
    }

    private static Category category(ModelNode n, ModelGraph g) {
        if (n.id().equals(g.rootId()) || n.tag().equals("profile")) {
            return Category.MODEL;
        }
        if (n.tag().equals("folder")) {
            return Category.FOLDER;
        }
        if (Describer.isView(n.type())) {
            return Category.VIEW;
        }
        return n.type().endsWith("Relationship") ? Category.RELATION : Category.ELEMENT;
    }

    private void add(String verb, Category c, String label) {
        if (buckets.computeIfAbsent(verb + "|" + c.name(), k -> new LinkedHashSet<>()).add(label)) {
            total++;
        }
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public int count() {
        return total;
    }

    /** e.g. "Changed Business Actor 'Customer', added 2 views". */
    @Override
    public String toString() {
        if (total == 0) {
            return "No changes";
        }
        List<String> parts = new ArrayList<>();
        buckets.forEach((key, labels) -> {
            String verb = key.substring(0, key.indexOf('|'));
            Category c = Category.valueOf(key.substring(key.indexOf('|') + 1));
            String text = labels.size() == 1 ? labels.iterator().next()
                    : labels.size() + " " + c.noun + "s";
            parts.add((parts.isEmpty() ? verb : verb.toLowerCase()) + " " + text);
        });
        String joined = String.join(", ", parts);
        return joined.length() > 200 ? joined.substring(0, 197) + "..." : joined;
    }
}
