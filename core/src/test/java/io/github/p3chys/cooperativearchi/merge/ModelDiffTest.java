package io.github.p3chys.cooperativearchi.merge;

import static io.github.p3chys.cooperativearchi.TestModels.base;
import static io.github.p3chys.cooperativearchi.TestModels.edited;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.p3chys.cooperativearchi.merge.ModelDiff.Entry;
import io.github.p3chys.cooperativearchi.merge.ModelDiff.Kind;

class ModelDiffTest {

    private static final String CRM = "<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>";
    private static final String PROPERTY = "<property key=\"owner\" value=\"sales\"/>";
    private static final String ROLE_BOUNDS = "<bounds x=\"240\" y=\"24\" width=\"120\" height=\"55\"/>";

    @Test
    void listsEveryChangedValueOfAnElement() {
        ModelDiff d = ModelDiff.of(base(), edited("name=\"Customer\"", "name=\"Client\"",
                "<documentation>A customer</documentation>", "<documentation>Buys things\nand pays</documentation>",
                PROPERTY, "<property key=\"owner\" value=\"marketing\"/><property key=\"risk\" value=\"high\"/>"));

        assertThat(d.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(Kind.CHANGED);
            assertThat(e.category()).isEqualTo("Elements");
            assertThat(e.item()).isEqualTo("Business Actor 'Client'");
            assertThat(e.changes()).extracting(Object::toString).containsExactly(
                    "Name: Customer → Client",
                    "Documentation: A customer → Buys things ↵ and pays",
                    "Property 'owner': sales → marketing",
                    "Property 'risk': (none) → high");
        });
        assertThat(d.layoutChanges()).isZero();
    }

    @Test
    void addedAndDeletedObjectsAreListedWithTheirContent() {
        ModelDiff d = ModelDiff.of(base(), edited(
                CRM, CRM + "<element xsi:type=\"archimate:ApplicationService\" name=\"Billing\" id=\"id-bill\">"
                        + "<documentation>Sends invoices</documentation><property key=\"cost\" value=\"10\"/></element>",
                "<element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/>", "",
                "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\" source=\"id-crm\" target=\"id-proc\"/>",
                "<element xsi:type=\"archimate:ServingRelationship\" name=\"bills\" id=\"id-r3\" source=\"id-bill\" target=\"id-actor\"/>"));

        assertThat(d.entries()).extracting(Entry::toString).containsExactly(
                "+ Application Service 'Billing'",
                "- Business Process 'Handle Claim'",
                "+ Serving relation 'bills' (Billing → Customer)",
                "- Serving relation 'CRM → Handle Claim'");
        assertThat(d.entries().get(0).changes()).extracting(Object::toString)
                .containsExactly("Documentation: Sends invoices", "Property 'cost': 10");
        assertThat(d.entries().get(2).changes()).isEmpty();
    }

    @Test
    void referencesAndLocationAreShownByName() {
        ModelDiff d = ModelDiff.of(base(), edited(
                "source=\"id-crm\" target=\"id-proc\"", "source=\"id-crm\" target=\"id-role\"",
                CRM, "",
                "<element xsi:type=\"archimate:BusinessRole\" name=\"Insurant\" id=\"id-role\"/>",
                "<element xsi:type=\"archimate:BusinessRole\" name=\"Insurant\" id=\"id-role\"/>" + CRM));

        assertThat(d.toText()).isEqualTo("""
                Elements
                  ~ Application Component 'CRM'
                      Location: Folder 'Application' → Folder 'Sub'
                Relationships
                  ~ Serving relation 'CRM → Insurant'
                      Target: 'Handle Claim' → 'Insurant'""");
    }

    @Test
    void layoutOnlyChangesAreCountedButNotListed() {
        ModelDiff d = ModelDiff.of(base(), edited(ROLE_BOUNDS, "<bounds x=\"300\" y=\"80\" width=\"120\" height=\"55\"/>"));

        assertThat(d.entries()).isEmpty();
        assertThat(d.layoutChanges()).isEqualTo(1);
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.toText()).isEqualTo("Positions or sizes changed for 1 object in views (not listed)");
    }

    @Test
    void viewContentChangesAreListedUnderTheView() {
        ModelDiff d = ModelDiff.of(base(), edited(
                "<content>Hello note</content>", "<content>Bye note</content>",
                "archimateElement=\"id-crm\" type=\"1\"", "archimateElement=\"id-crm\" type=\"1\" fillColor=\"#ff0000\"",
                ROLE_BOUNDS, "<bounds x=\"1\" y=\"2\" width=\"120\" height=\"55\"/>",
                "<child xsi:type=\"archimate:DiagramModelReference\" id=\"id-ref\" model=\"id-view2\">\n"
                        + "        <bounds x=\"480\" y=\"24\" width=\"120\" height=\"55\"/>\n      </child>",
                "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-proc\" archimateElement=\"id-proc\">"
                        + "<bounds x=\"480\" y=\"24\" width=\"120\" height=\"55\"/></child>",
                "name=\"Main View\"", "name=\"Main View\" viewpoint=\"layered\""));

        assertThat(d.entries()).singleElement().satisfies(e -> {
            assertThat(e.toString()).isEqualTo("~ View 'Main View'");
            assertThat(e.changes()).extracting(Object::toString).containsExactly(
                    "Viewpoint: (none) → layered",
                    "'CRM' › Fill colour: (none) → #ff0000",
                    "Note › Text: Hello note → Bye note",
                    "+ 'Handle Claim'",
                    "- Reference to view 'Second View'");
        });
        assertThat(d.layoutChanges()).isEqualTo(1);
    }

    @Test
    void firstUploadListsTheWholeModel() {
        ModelDiff d = ModelDiff.of(null, base());

        assertThat(d.entries()).allSatisfy(e -> assertThat(e.kind()).isEqualTo(Kind.ADDED));
        assertThat(d.entries()).extracting(Entry::toString).contains("+ Business Actor 'Customer'",
                "+ Assignment relation 'Customer → Insurant'", "+ Model 'Test Model'");
        Entry view = d.entries().stream().filter(e -> e.item().equals("View 'Main View'")).findFirst().orElseThrow();
        assertThat(view.changes()).extracting(Object::toString).containsExactly("+ 'Customer'",
                "+ Connection 'Customer → Insurant'", "+ 'Insurant'", "+ Group 'Group'", "+ 'CRM'", "+ Note", "+ Reference to view 'Second View'");
    }

    @Test
    void sameModelHasNoDifferences() {
        ModelDiff d = ModelDiff.of(base(), base());
        assertThat(d.isEmpty()).isTrue();
        assertThat(d.toText()).isEqualTo("No changes");
    }
}
