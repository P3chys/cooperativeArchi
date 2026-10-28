package io.github.p3chys.cooperativearchi.git;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Consecutive versions by one analyst, each within a short gap of the next (live collaboration saves every few
 * seconds). Merge versions join the group around them. Entries are newest first, like {@link SharedModel#history}.
 */
public record HistoryGroup(List<HistoryEntry> entries) {

    public static final Duration GAP = Duration.ofMinutes(10);

    public HistoryGroup {
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A history group needs at least one version");
        }
    }

    /** Groups a newest-first history; versions stay in order. */
    public static List<HistoryGroup> of(List<HistoryEntry> history, Duration gap) {
        List<HistoryGroup> groups = new ArrayList<>();
        List<HistoryEntry> current = new ArrayList<>();
        for (HistoryEntry e : history) {
            if (!current.isEmpty() && !joins(current, e, gap)) {
                groups.add(new HistoryGroup(current));
                current = new ArrayList<>();
            }
            current.add(e);
        }
        if (!current.isEmpty()) {
            groups.add(new HistoryGroup(current));
        }
        return groups;
    }

    private static boolean joins(List<HistoryEntry> group, HistoryEntry e, Duration gap) {
        HistoryEntry last = group.get(group.size() - 1);
        if (Duration.between(e.time(), last.time()).abs().compareTo(gap) > 0) {
            return false;
        }
        return e.merge() || sameAuthor(author(group), e);
    }

    /** The first non-merge version decides who the group belongs to; a merge-only group belongs to its newest. */
    private static HistoryEntry author(List<HistoryEntry> group) {
        return group.stream().filter(e -> !e.merge()).findFirst().orElse(group.get(0));
    }

    private static boolean sameAuthor(HistoryEntry a, HistoryEntry b) {
        if (a.email() != null && !a.email().isBlank() && b.email() != null && !b.email().isBlank()) {
            return a.email().equalsIgnoreCase(b.email());
        }
        return a.author().equals(b.author());
    }

    public HistoryEntry newest() {
        return entries.get(0);
    }

    public HistoryEntry oldest() {
        return entries.get(entries.size() - 1);
    }

    public int size() {
        return entries.size();
    }

    public String author() {
        return author(entries).author();
    }

    public String email() {
        return author(entries).email();
    }

    /** Merge-only group (shown dimmed). */
    public boolean merge() {
        return entries.stream().allMatch(HistoryEntry::merge);
    }

    public boolean uploaded() {
        return entries.stream().allMatch(HistoryEntry::uploaded);
    }

    /** Contains the version you have. */
    public boolean current() {
        return entries.stream().anyMatch(HistoryEntry::current);
    }

    /** The distinct first lines of the non-merge messages, newest first, at most {@code max} of them. */
    public String summary(int max) {
        Set<String> lines = new LinkedHashSet<>();
        for (HistoryEntry e : entries) {
            if (!e.merge()) {
                lines.add(e.message().lines().findFirst().orElse(""));
            }
        }
        if (lines.isEmpty()) {
            return newest().message().lines().findFirst().orElse("");
        }
        List<String> shown = lines.stream().limit(max).toList();
        return String.join("; ", shown) + (lines.size() > max ? "; …" : "");
    }
}
