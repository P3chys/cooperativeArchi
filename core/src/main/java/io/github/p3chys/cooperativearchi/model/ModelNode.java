package io.github.p3chys.cooperativearchi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.p3chys.cooperativearchi.xml.XNode;

/**
 * One identifiable object of an Archi model (model, folder, element, relationship, view, diagram object,
 * connection, profile...). Sub-elements without an id (documentation, property, bounds, bendpoint...) are
 * kept as "fields" grouped by tag. Children with ids are referenced by id, so the model is a flat map.
 */
public record ModelNode(String id, String tag, String parentId, Map<String, String> attrs,
        Map<String, List<XNode>> fields, List<String> childIds) {

    public ModelNode {
        attrs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        Map<String, List<XNode>> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (!v.isEmpty()) {
                f.put(k, List.copyOf(v));
            }
        });
        fields = java.util.Collections.unmodifiableMap(f);
        childIds = List.copyOf(childIds);
    }

    public String type() {
        String t = attrs.get("xsi:type");
        return t == null ? tag : t.substring(t.indexOf(':') + 1);
    }

    public String name() {
        return attrs.get("name");
    }

    public ModelNode withParent(String newParent) {
        return new ModelNode(id, tag, newParent, attrs, fields, childIds);
    }

    public ModelNode withChildIds(List<String> ids) {
        return new ModelNode(id, tag, parentId, attrs, fields, ids);
    }

    public ModelNode withAttrs(Map<String, String> newAttrs) {
        return new ModelNode(id, tag, parentId, newAttrs, fields, childIds);
    }

    public ModelNode withFields(Map<String, List<XNode>> newFields) {
        return new ModelNode(id, tag, parentId, attrs, newFields, childIds);
    }

    /** Same content ignoring the order of children (used to detect real edits of a node). */
    public boolean sameContent(ModelNode other) {
        return other != null && tag.equals(other.tag) && java.util.Objects.equals(parentId, other.parentId)
                && attrs.equals(other.attrs) && fields.equals(other.fields);
    }

    List<XNode> fieldList() {
        List<XNode> all = new ArrayList<>();
        fields.values().forEach(all::addAll);
        return all;
    }
}
