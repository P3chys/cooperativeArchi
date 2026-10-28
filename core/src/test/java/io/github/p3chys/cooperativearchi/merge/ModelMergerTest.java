package io.github.p3chys.cooperativearchi.merge;

import static io.github.p3chys.cooperativearchi.TestModels.base;
import static io.github.p3chys.cooperativearchi.TestModels.edited;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.p3chys.cooperativearchi.TestModels;
import io.github.p3chys.cooperativearchi.merge.Conflict.Kind;
import io.github.p3chys.cooperativearchi.merge.Conflict.Side;
import io.github.p3chys.cooperativearchi.merge.ModelMerger.MergeSession;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.ModelNode;

class ModelMergerTest {

    private static final String CRM = "<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>";
    private static final String ROLE_BOUNDS = "<bounds x=\"240\" y=\"24\" width=\"120\" height=\"55\"/>";
    private static final String PROPERTY = "<property key=\"owner\" value=\"sales\"/>";

    @Test
    void differentElementsMergeSilently() {
        ModelGraph mine = edited("name=\"Customer\"", "name=\"Client\"");
        ModelGraph theirs = edited("name=\"CRM\"", "name=\"Salesforce\"");

        ModelGraph merged = ModelMerger.mergeClean(base(), mine, theirs);

        assertThat(merged.get("id-actor").name()).isEqualTo("Client");
        assertThat(merged.get("id-crm").name()).isEqualTo("Salesforce");
    }

    @Test
    void differentAttributesOfSameElementMergeSilently() {
        ModelGraph mine = edited("name=\"Customer\"", "name=\"Client\"");
        ModelGraph theirs = edited("<documentation>A customer</documentation>", "<documentation>Buys things</documentation>");

        ModelGraph merged = ModelMerger.mergeClean(base(), mine, theirs);

        ModelNode actor = merged.get("id-actor");
        assertThat(actor.name()).isEqualTo("Client");
        assertThat(actor.fields().get("documentation").get(0).text()).isEqualTo("Buys things");
    }

    @Test
    void differentPropertiesMergeByKey() {
        ModelGraph mine = edited(PROPERTY, PROPERTY + "<property key=\"cost\" value=\"10\"/>");
        ModelGraph theirs = edited(PROPERTY, "<property key=\"owner\" value=\"marketing\"/><property key=\"risk\" value=\"high\"/>");

        ModelGraph merged = ModelMerger.mergeClean(base(), mine, theirs);

        assertThat(merged.get("id-actor").fields().get("property"))
                .extracting(x -> x.attr("key") + "=" + x.attr("value"))
                .containsExactly("owner=marketing", "cost=10", "risk=high");
    }

    @Test
    void sameNameChangedDifferentlyIsAConflictAndChoiceIsApplied() {
        ModelGraph mine = edited("name=\"Customer\"", "name=\"Client\"");
        ModelGraph theirs = edited("name=\"Customer\"", "name=\"Buyer\"");

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);
        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.ATTRIBUTE);
            assertThat(c.item()).isEqualTo("Business Actor 'Client'");
            assertThat(c.what()).isEqualTo("Name");
            assertThat(c.mine()).isEqualTo("Client");
            assertThat(c.theirs()).isEqualTo("Buyer");
        });

        s.conflicts().get(0).choose(Side.THEIRS);
        assertThat(s.finish().get("id-actor").name()).isEqualTo("Buyer");
    }

    @Test
    void sameChangeOnBothSidesIsNoConflict() {
        ModelGraph mine = edited("name=\"Customer\"", "name=\"Client\"");
        ModelGraph theirs = edited("name=\"Customer\"", "name=\"Client\"");

        assertThat(ModelMerger.prepare(base(), mine, theirs).conflicts()).isEmpty();
    }

    @Test
    void bothAddObjectsToTheSameView() {
        String mineObj = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-new1\" archimateElement=\"id-proc\"><bounds x=\"600\" y=\"200\" width=\"120\" height=\"55\"/></child>";
        String theirsObj = "<child xsi:type=\"archimate:Note\" id=\"id-new2\"><bounds x=\"700\" y=\"300\" width=\"120\" height=\"55\"/><content>Their note</content></child>";
        String anchor = "<child xsi:type=\"archimate:DiagramModelReference\"";
        ModelGraph mine = edited(anchor, mineObj + anchor);
        ModelGraph theirs = edited(anchor, theirsObj + anchor);

        ModelGraph merged = ModelMerger.mergeClean(base(), mine, theirs);

        assertThat(merged.get("id-view").childIds()).containsExactly("id-do-actor", "id-do-role", "id-grp", "id-new1",
                "id-new2", "id-ref");
    }

    @Test
    void bothMovingTheSameBoxIsAConflict() {
        ModelGraph mine = edited(ROLE_BOUNDS, "<bounds x=\"300\" y=\"24\" width=\"120\" height=\"55\"/>");
        ModelGraph theirs = edited(ROLE_BOUNDS, "<bounds x=\"240\" y=\"90\" width=\"120\" height=\"55\"/>");

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);

        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.item()).isEqualTo("'Insurant' in view 'Main View'");
            assertThat(c.what()).isEqualTo("Position / size");
            assertThat(c.mine()).isEqualTo("x=300 y=24 w=120 h=55");
        });
    }

    @Test
    void deletedByThemButChangedByMeIsAConflict() {
        ModelGraph mine = edited("name=\"CRM\"", "name=\"CRM 2\"");
        ModelGraph theirs = withoutCrm();

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);
        assertThat(s.conflicts()).singleElement().extracting(Conflict::kind).isEqualTo(Kind.DELETED_BY_THEM);

        // keep mine (default): element and its usages stay
        ModelGraph kept = s.finish();
        assertThat(kept.contains("id-crm")).isTrue();

        MergeSession s2 = ModelMerger.prepare(base(), mine, theirs);
        s2.conflicts().get(0).choose(Side.THEIRS);
        ModelGraph deleted = s2.finish();
        assertThat(deleted.contains("id-crm")).isFalse();
        assertThat(deleted.contains("id-r2")).isFalse();
        assertThat(deleted.contains("id-do-crm")).isFalse();
    }

    @Test
    void deletedElementStillUsedByOtherSideIsRestored() {
        // They delete CRM (with its relation and diagram object); I add a new relation to CRM.
        String rel = "<element xsi:type=\"archimate:FlowRelationship\" id=\"id-r3\" source=\"id-crm\" target=\"id-actor\"/>";
        String anchor = "<element xsi:type=\"archimate:ServingRelationship\"";
        ModelGraph mine = edited(anchor, rel + anchor);
        ModelGraph theirs = withoutCrm();

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);
        assertThat(s.conflicts()).isEmpty();
        ModelGraph merged = s.finish();

        assertThat(merged.contains("id-crm")).isTrue();
        assertThat(merged.contains("id-r3")).isTrue();
        assertThat(merged.contains("id-r2")).isFalse();
        assertThat(s.notes()).singleElement().asString().contains("Application Component 'CRM'");
    }

    @Test
    void deletingAViewSomeoneEditedIsOneConflictForTheWholeView() {
        String view2 = "<element xsi:type=\"archimate:ArchimateDiagramModel\" name=\"Second View\" id=\"id-view2\"/>";
        String ref = "<child xsi:type=\"archimate:DiagramModelReference\" id=\"id-ref\" model=\"id-view2\">\n"
                + "        <bounds x=\"480\" y=\"24\" width=\"120\" height=\"55\"/>\n      </child>\n";
        ModelGraph mine = edited(ROLE_BOUNDS, "<bounds x=\"300\" y=\"24\" width=\"120\" height=\"55\"/>",
                "name=\"Group\"", "name=\"Group 2\"");
        ModelGraph theirs = withoutMainView();

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);

        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.DELETED_BY_THEM);
            assertThat(c.item()).isEqualTo("View 'Main View'");
        });
        ModelGraph kept = s.finish();
        assertThat(kept.get("id-view").childIds()).contains("id-do-actor", "id-do-role", "id-grp");
    }

    @Test
    void movedToDifferentFoldersIsAConflict() {
        String sub = "<folder name=\"Sub\" id=\"id-f-sub\">";
        String other = "<folder name=\"Other Sub\" id=\"id-f-sub2\"/>";
        String proc = "<element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/>";
        ModelGraph mine = edited(proc, "", sub, sub + proc);
        ModelGraph theirs = edited(proc, other.replace("/>", ">") + proc + "</folder>");

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);

        assertThat(s.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.PARENT);
            assertThat(c.mine()).isEqualTo("Folder 'Sub'");
            assertThat(c.theirs()).isEqualTo("Folder 'Other Sub'");
        });
        s.conflicts().get(0).choose(Side.THEIRS);
        assertThat(s.finish().get("id-proc").parentId()).isEqualTo("id-f-sub2");
    }

    @Test
    void zOrderChangedOnlyByThemIsTaken() {
        assertThat(ModelMerger.mergeOrder(List.of("a", "b", "c"), List.of("a", "b", "c", "x"), List.of("c", "a", "b"),
                Set.of("a", "b", "c", "x"))).containsExactly("c", "a", "b", "x");
        assertThat(ModelMerger.mergeOrder(List.of("a", "b"), List.of("b", "a"), List.of("a", "y", "b"),
                Set.of("a", "b", "y"))).containsExactly("y", "b", "a");
    }

    @Test
    void summaryDescribesChanges() {
        ModelGraph after = edited("name=\"Customer\"", "name=\"Client\"", ROLE_BOUNDS,
                "<bounds x=\"300\" y=\"24\" width=\"120\" height=\"55\"/>");
        assertThat(ChangeSummary.of(base(), after)).hasToString("Changed Business Actor 'Client', changed View 'Main View'");
    }

    private static ModelGraph withoutCrm() {
        String doCrm = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-crm\" archimateElement=\"id-crm\" type=\"1\">\n"
                + "          <bounds x=\"12\" y=\"36\" width=\"120\" height=\"55\"/>\n        </child>";
        return edited(CRM, "", "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\" source=\"id-crm\" target=\"id-proc\"/>", "",
                doCrm, "");
    }

    /** Removes "Main View" with all of its content. */
    private static ModelGraph withoutMainView() {
        String text = TestModels.text();
        int start = text.indexOf("<element xsi:type=\"archimate:ArchimateDiagramModel\" name=\"Main View\"");
        int end = text.indexOf("<element xsi:type=\"archimate:ArchimateDiagramModel\" name=\"Second View\"");
        return TestModels.graph(text.substring(0, start) + text.substring(end));
    }
}
