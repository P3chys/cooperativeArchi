package io.github.p3chys.cooperativearchi.merge;

import java.util.List;
import java.util.stream.Collectors;

import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.ModelNode;
import io.github.p3chys.cooperativearchi.xml.XNode;

/** Turns model nodes and changes into text an analyst understands. */
public final class Describer {

    private Describer() {
    }

    public static String describe(ModelNode n, ModelGraph g) {
        if (n == null) {
            return "(unknown)";
        }
        String type = n.type();
        if (n.id().equals(g.rootId())) {
            return "Model '" + nvl(n.name()) + "'";
        }
        if (n.tag().equals("folder")) {
            return "Folder '" + nvl(n.name()) + "'";
        }
        if (n.tag().equals("profile")) {
            return "Specialization '" + nvl(n.name()) + "'";
        }
        if (isView(type)) {
            return "View '" + nvl(n.name()) + "'";
        }
        ModelNode view = g.enclosingView(n.id());
        String in = view == null ? "" : " in view '" + nvl(view.name()) + "'";
        String element = n.attrs().get("archimateElement");
        if (element != null) {
            return "'" + nameOf(element, g) + "'" + in;
        }
        String relation = n.attrs().get("archimateRelationship");
        if (relation != null) {
            return "Connection " + relationLabel(g.get(relation), g) + in;
        }
        if (view != null) {
            String nm = n.name() == null || n.name().isEmpty() ? "" : " '" + n.name() + "'";
            return humanize(type) + nm + in;
        }
        if (type.endsWith("Relationship")) {
            return humanize(type.replace("Relationship", " relation")) + " " + relationLabel(n, g);
        }
        return humanize(type) + " '" + nvl(n.name()) + "'";
    }

    public static boolean isView(String type) {
        return type.endsWith("DiagramModel") || type.equals("SketchModel") || type.equals("CanvasModel");
    }

    private static String relationLabel(ModelNode rel, ModelGraph g) {
        if (rel == null) {
            return "";
        }
        String label = nameOf(rel.attrs().get("source"), g) + " → " + nameOf(rel.attrs().get("target"), g);
        return rel.name() == null || rel.name().isEmpty() ? "'" + label + "'" : "'" + rel.name() + "' (" + label + ")";
    }

    private static String nameOf(String id, ModelGraph g) {
        ModelNode n = id == null ? null : g.get(id);
        if (n == null) {
            return "?";
        }
        if (n.attrs().containsKey("archimateElement")) {
            return nameOf(n.attrs().get("archimateElement"), g);
        }
        return n.name() == null || n.name().isEmpty() ? humanize(n.type()) : n.name();
    }

    /** "BusinessActor" -> "Business Actor". */
    public static String humanize(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    public static String label(String key) {
        if (key.startsWith("property:")) {
            return "Property '" + key.substring(9) + "'";
        }
        if (key.startsWith("feature:")) {
            return "Feature '" + key.substring(8) + "'";
        }
        return switch (key) {
            case "name" -> "Name";
            case "documentation" -> "Documentation";
            case "content" -> "Text";
            case "purpose" -> "Purpose";
            case "bounds" -> "Position / size";
            case "bendpoint" -> "Line bend points";
            case "property" -> "Properties";
            case "source" -> "Source";
            case "target" -> "Target";
            case "parent" -> "Location";
            case "fillColor" -> "Fill colour";
            case "lineColor" -> "Line colour";
            case "fontColor" -> "Font colour";
            default -> humanize(key.substring(0, 1).toUpperCase() + key.substring(1));
        };
    }

    public static String value(String v) {
        return v == null ? "(none)" : v.isEmpty() ? "(empty)" : v;
    }

    public static String fields(List<XNode> list) {
        if (list == null || list.isEmpty()) {
            return "(none)";
        }
        return list.stream().map(Describer::field).collect(Collectors.joining("; "));
    }

    public static String field(XNode x) {
        if (x == null) {
            return "(none)";
        }
        if (!x.text().isEmpty()) {
            return x.text();
        }
        if (x.tag().equals("bounds")) {
            return "x=" + x.attrs().getOrDefault("x", "0") + " y=" + x.attrs().getOrDefault("y", "0") + " w="
                    + x.attrs().getOrDefault("width", "-1") + " h=" + x.attrs().getOrDefault("height", "-1");
        }
        if (x.attrs().containsKey("key")) {
            return x.attrs().get("key") + "=" + x.attrs().getOrDefault("value", "");
        }
        if (x.attrs().containsKey("name") && x.attrs().containsKey("value")) {
            return x.attrs().get("name") + "=" + x.attrs().get("value");
        }
        return x.attrs().toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
