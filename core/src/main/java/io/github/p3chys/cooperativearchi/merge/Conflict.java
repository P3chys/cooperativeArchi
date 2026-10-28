package io.github.p3chys.cooperativearchi.merge;

import io.github.p3chys.cooperativearchi.xml.XNode;

/**
 * A change made by both sides to the same thing. The user picks {@link Side#MINE} or {@link Side#THEIRS};
 * until then {@link #choice()} is {@link Side#MINE}.
 */
public final class Conflict {

    public enum Kind {
        /** An XML attribute such as name, fillColor, source. */
        ATTRIBUTE,
        /** A sub-element such as documentation, bounds, bendpoints or one property (key = "property:owner"). */
        FIELD,
        /** Moved to different folders / groups. */
        PARENT,
        /** I deleted it, they changed it. MINE = delete, THEIRS = keep. */
        DELETED_BY_ME,
        /** They deleted it, I changed it. MINE = keep, THEIRS = delete. */
        DELETED_BY_THEM
    }

    public enum Side {
        MINE, THEIRS
    }

    private final String nodeId;
    private final Kind kind;
    private final String key;
    private final String item;
    private final String what;
    private final String mine;
    private final String theirs;
    final Object theirsValue;
    final String fieldKeyAttr;
    final XNode theirsField;
    private Side choice = Side.MINE;

    Conflict(String nodeId, Kind kind, String key, String item, String what, String mine, String theirs,
            Object theirsValue, String fieldKeyAttr, XNode theirsField) {
        this.nodeId = nodeId;
        this.kind = kind;
        this.key = key;
        this.item = item;
        this.what = what;
        this.mine = mine;
        this.theirs = theirs;
        this.theirsValue = theirsValue;
        this.fieldKeyAttr = fieldKeyAttr;
        this.theirsField = theirsField;
    }

    public String nodeId() {
        return nodeId;
    }

    public Kind kind() {
        return kind;
    }

    /** Attribute name, field tag ("documentation") or "property:key". */
    public String key() {
        return key;
    }

    /** Human readable item, e.g. "Business Actor 'Customer'". */
    public String item() {
        return item;
    }

    /** Human readable aspect, e.g. "Name" or "Deleted". */
    public String what() {
        return what;
    }

    /** Human readable value on my side. */
    public String mine() {
        return mine;
    }

    /** Human readable value on their side. */
    public String theirs() {
        return theirs;
    }

    public Side choice() {
        return choice;
    }

    public void choose(Side side) {
        this.choice = side;
    }

    @Override
    public String toString() {
        return item + " / " + what + ": mine=" + mine + " theirs=" + theirs;
    }
}
