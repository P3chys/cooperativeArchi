package io.github.p3chys.cooperativearchi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.p3chys.cooperativearchi.TestModels;
import io.github.p3chys.cooperativearchi.xml.Xml;

class SplitLayoutTest {

    @Test
    void writesOneFilePerElementAndFolderDirectories() throws Exception {
        Map<String, byte[]> files = SplitLayout.write(TestModels.base());

        assertThat(files).containsKeys("model/model.xml", "model/business/folder.xml", "model/business/id-actor.xml",
                "model/business/id-f-sub/folder.xml", "model/business/id-f-sub/id-role.xml",
                "model/relations/id-r1.xml", "model/diagrams/id-view.xml", "model.archimate");
        assertThat(new String(files.get("model/diagrams/id-view.xml"), StandardCharsets.UTF_8))
                .contains("id-do-crm", "Hello note");
        assertThat(new String(files.get("model/model.xml"), StandardCharsets.UTF_8))
                .contains("Test purpose", "id-prof").doesNotContain("id-actor");
    }

    @Test
    void roundTripPreservesModel() throws Exception {
        ModelGraph original = SplitLayout.canonical(TestModels.base());
        ModelGraph back = SplitLayout.read(SplitLayout.write(original));

        assertThat(Xml.write(back.toXml())).isEqualTo(Xml.write(original.toXml()));
    }

    @Test
    void writeIsDeterministicRegardlessOfArchiElementOrder() throws Exception {
        // Archi may save folder children in any order; the repository content must not change.
        String reordered = TestModels.edit(TestModels.text(),
                "<element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/>\n", "");
        reordered = reordered.replace("<folder name=\"Sub\" id=\"id-f-sub\">",
                "<element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/>\n<folder name=\"Sub\" id=\"id-f-sub\">");
        Map<String, byte[]> a = SplitLayout.write(TestModels.base());
        Map<String, byte[]> b = SplitLayout.write(TestModels.graph(reordered));

        assertThat(a.keySet()).isEqualTo(b.keySet());
        a.forEach((path, bytes) -> assertThat(b.get(path)).as(path).isEqualTo(bytes));
    }

    @Test
    void snapshotIsAValidSingleFileModel() throws Exception {
        byte[] snapshot = SplitLayout.write(TestModels.base()).get(SplitLayout.SNAPSHOT_FILE);
        ModelGraph g = ModelGraph.read(snapshot);

        assertThat(g.nodes()).hasSameSizeAs(TestModels.base().nodes());
        assertThat(new String(snapshot, StandardCharsets.UTF_8)).startsWith("<?xml").contains("archimate:model");
    }

    @Test
    void archiveModelWithImagesRoundTrips() throws Exception {
        String withImage = TestModels.edit(TestModels.text(), "<child xsi:type=\"archimate:Note\" id=\"id-note\">",
                "<child xsi:type=\"archimate:Note\" id=\"id-note\" imagePath=\"images/abc.png\">");
        ModelGraph g = TestModels.graph(withImage);
        g.images().put("images/abc.png", new byte[] { 1, 2, 3 });

        byte[] archive = g.write();
        assertThat(archive[0]).isEqualTo((byte) 'P');
        ModelGraph back = SplitLayout.read(SplitLayout.write(ModelGraph.read(archive)));
        assertThat(back.images()).containsKey("images/abc.png");
        assertThat(back.get("id-note").attrs()).containsEntry("imagePath", "images/abc.png");
    }
}
