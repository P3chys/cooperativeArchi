package io.github.p3chys.cooperativearchi.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class HistoryGroupTest {

    static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    int ids;

    HistoryEntry entry(String author, int minutesAgo, String message) {
        return entry(author, minutesAgo, message, false);
    }

    HistoryEntry entry(String author, int minutesAgo, String message, boolean merge) {
        String hex = String.format("%040x", ++ids);
        return new HistoryEntry(ObjectId.fromString(hex), message, author, author.toLowerCase() + "@example.com",
                T0.minusSeconds(minutesAgo * 60L), merge, true, false);
    }

    List<Integer> sizes(List<HistoryEntry> history) {
        return HistoryGroup.of(history, HistoryGroup.GAP).stream().map(HistoryGroup::size).toList();
    }

    @Test
    void consecutiveVersionsBySameAuthorWithinGapAreOneGroup() {
        List<HistoryEntry> h = List.of(entry("Alice", 0, "c"), entry("Alice", 9, "b"), entry("Alice", 18, "a"));
        List<HistoryGroup> groups = HistoryGroup.of(h, HistoryGroup.GAP);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).newest()).isSameAs(h.get(0));
        assertThat(groups.get(0).oldest()).isSameAs(h.get(2));
        assertThat(groups.get(0).author()).isEqualTo("Alice");
    }

    @Test
    void gapLongerThanTenMinutesSplits() {
        assertThat(sizes(List.of(entry("Alice", 0, "b"), entry("Alice", 11, "a")))).containsExactly(1, 1);
        assertThat(sizes(List.of(entry("Alice", 0, "b"), entry("Alice", 10, "a")))).containsExactly(2);
    }

    @Test
    void anotherAuthorSplits() {
        assertThat(sizes(List.of(entry("Alice", 0, "a2"), entry("Bob", 1, "b1"), entry("Alice", 2, "a1"))))
                .containsExactly(1, 1, 1);
    }

    @Test
    void mergesFoldIntoTheSurroundingGroup() {
        List<HistoryEntry> h = List.of(entry("Alice", 0, "a3"), entry("Alice", 1, "Merge", true), entry("Alice", 2, "a2"),
                entry("Bob", 3, "Merge Alice", true), entry("Alice", 4, "a1"), entry("Bob", 30, "b1"));
        List<HistoryGroup> groups = HistoryGroup.of(h, HistoryGroup.GAP);
        assertThat(groups).extracting(HistoryGroup::size).containsExactly(5, 1);
        assertThat(groups.get(0).author()).isEqualTo("Alice");
        assertThat(groups.get(0).merge()).isFalse();
    }

    @Test
    void mergeOnlyGroupBelongsToItsAuthor() {
        List<HistoryGroup> groups = HistoryGroup.of(List.of(entry("Bob", 0, "Merge", true), entry("Alice", 1, "a1")),
                HistoryGroup.GAP);
        assertThat(groups).extracting(HistoryGroup::author).containsExactly("Bob", "Alice");
        assertThat(groups.get(0).merge()).isTrue();
    }

    @Test
    void summaryListsDistinctMessagesNewestFirst() {
        HistoryGroup g = HistoryGroup.of(List.of(entry("Alice", 0, "Autosave"), entry("Alice", 1, "Merge", true),
                entry("Alice", 2, "Autosave\n\ndetails"), entry("Alice", 3, "Renamed X"), entry("Alice", 4, "Added Y")),
                HistoryGroup.GAP).get(0);
        assertThat(g.summary(2)).isEqualTo("Autosave; Renamed X; …");
        assertThat(g.summary(3)).isEqualTo("Autosave; Renamed X; Added Y");
    }

    @Test
    void emptyHistoryHasNoGroups() {
        assertThat(HistoryGroup.of(List.of(), HistoryGroup.GAP)).isEmpty();
    }
}
