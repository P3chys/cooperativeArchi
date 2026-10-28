package io.github.p3chys.cooperativearchi.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal immutable XML element: tag, attributes, text and child elements.
 * Equality is structural, which is what the merge relies on.
 */
public record XNode(String tag, Map<String, String> attrs, String text, List<XNode> children) {

    public XNode {
        attrs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        text = text == null ? "" : text;
        children = List.copyOf(children);
    }

    public static XNode of(String tag, Map<String, String> attrs) {
        return new XNode(tag, attrs, "", List.of());
    }

    public String attr(String name) {
        return attrs.get(name);
    }

    public String id() {
        return attrs.get("id");
    }

    public XNode withAttrs(Map<String, String> newAttrs) {
        return new XNode(tag, newAttrs, text, children);
    }

    public XNode withChildren(List<XNode> newChildren) {
        return new XNode(tag, attrs, text, newChildren);
    }

    public XNode withoutAttr(String name) {
        if (!attrs.containsKey(name)) {
            return this;
        }
        Map<String, String> copy = new LinkedHashMap<>(attrs);
        copy.remove(name);
        return withAttrs(copy);
    }

    public List<XNode> childrenNamed(String name) {
        List<XNode> result = new ArrayList<>();
        for (XNode child : children) {
            if (child.tag.equals(name)) {
                result.add(child);
            }
        }
        return result;
    }
}
