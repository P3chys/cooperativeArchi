package io.github.p3chys.cooperativearchi.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.p3chys.cooperativearchi.TestModels;

/** Live collaboration: polling the server for colleagues' uploads and quick sync round trips on a large model. */
class LiveSyncTest {

    @TempDir
    Path tmp;
    String remote;
    SyncOptions alice;
    SyncOptions bob;

    @BeforeEach
    void setUp() throws Exception {
        Path remoteDir = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remoteDir.toFile()).setInitialBranch("main").call().close();
        remote = remoteDir.toUri().toString();
        alice = SyncOptions.of(null, new PersonIdent("Alice", "alice@example.com"), ConflictResolver.FAIL);
        bob = SyncOptions.of(null, new PersonIdent("Bob", "bob@example.com"), ConflictResolver.FAIL);
    }

    @Test
    void remoteMovedSeesColleaguesUploads() throws Exception {
        try (SharedModel a = share(TestModels.text()); SharedModel b = SharedModel.cloneFrom(remote, tmp.resolve("bob"), bob)) {
            assertThat(b.remoteMoved(bob)).isFalse();
            assertThat(b.remoteHead(bob)).isEqualTo(a.history(1).get(0).id());

            edit(a, "name=\"Customer\"", "name=\"Client\"");
            assertThat(a.sync(alice).pushed()).isTrue();
            assertThat(a.remoteMoved(alice)).isFalse();
            assertThat(b.remoteMoved(bob)).isTrue();

            SyncResult r = b.sync(bob);
            assertThat(r.received()).contains("Client");
            assertThat(b.remoteMoved(bob)).isFalse();
        }
    }

    @Test
    void remoteMovedIsFalseWithoutRemote() throws Exception {
        Path source = tmp.resolve("source.archimate");
        Files.writeString(source, TestModels.text());
        try (SharedModel a = SharedModel.share(source, tmp.resolve("alice"), null)) {
            a.sync(alice);
            assertThat(a.remoteMoved(alice)).isFalse();
            assertThat(a.remoteHead(alice)).isNull();
        }
    }

    /** ~2000 elements, ~2000 relationships, views with ~300 diagram objects; each sync opens the model afresh like the plugin. */
    @Test
    void roundTripOnLargeModelIsFast() throws Exception {
        try (SharedModel a = share(largeModel(2000, 2000, 3, 100))) {
            Path bobDir = tmp.resolve("bob");
            SharedModel.cloneFrom(remote, bobDir, bob).close();
            Path aFile = a.modelFile();
            Path bFile = SharedModel.modelFileIn(bobDir);

            // both edit at the same time every round, so every sync but the first is commit + fetch + 3-way merge + push
            long worst = 0;
            int rounds = 5;
            for (int round = 1; round <= rounds; round++) {
                edit(aFile, "name=\"Element " + round + "\"", "name=\"Alice " + round + "\"");
                edit(bFile, "name=\"Element " + (1000 + round) + "\"", "name=\"Bob " + (1000 + round) + "\"");

                long t0 = System.nanoTime();
                syncAndReload(aFile, alice);
                long t1 = System.nanoTime();
                boolean moved;
                try (SharedModel m = SharedModel.open(bFile)) {
                    moved = m.remoteMoved(bob);
                }
                long t2 = System.nanoTime();
                syncAndReload(bFile, bob);
                long t3 = System.nanoTime();
                assertThat(moved).isTrue();

                System.out.printf("live sync round %d: Alice sync %d ms, Bob poll %d ms, Bob sync %d ms%n", round,
                        ms(t1 - t0), ms(t2 - t1), ms(t3 - t2));
                worst = Math.max(worst, Math.max(t1 - t0, t3 - t2));
            }
            assertThat(Files.readString(bFile)).contains("Alice " + rounds, "Bob " + (1000 + rounds));
            assertThat(ms(worst)).isLessThan(5000);
        }
    }

    // ------------------------------------------------------------ helpers

    private SharedModel share(String xml) throws Exception {
        Path source = tmp.resolve("source.archimate");
        Files.writeString(source, xml);
        SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote);
        assertThat(a.sync(alice).pushed()).isTrue();
        return a;
    }

    private static void syncAndReload(Path file, SyncOptions o) throws Exception {
        try (SharedModel m = SharedModel.open(file)) {
            SyncResult r = m.sync(o);
            assertThat(r.pushed()).isTrue();
            assertThat(m.applySync(r)).isTrue();
        }
    }

    private static void edit(SharedModel m, String from, String to) throws Exception {
        edit(m.modelFile(), from, to);
    }

    private static void edit(Path file, String from, String to) throws Exception {
        String text = Files.readString(file);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to));
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    /** A generated model: elements spread over the layers, relationships between them and views showing some of both. */
    static String largeModel(int elements, int relations, int views, int objectsPerView) {
        String[][] layers = { { "business", "BusinessActor", "BusinessProcess", "BusinessObject" },
                { "application", "ApplicationComponent", "ApplicationService", "DataObject" },
                { "technology", "Node", "SystemSoftware", "Artifact" } };
        String[] relTypes = { "AssociationRelationship", "ServingRelationship", "FlowRelationship", "TriggeringRelationship" };
        StringBuilder sb = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <archimate:model xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
                xmlns:archimate="http://www.archimatetool.com/archimate" name="Large Model" id="id-model" version="5.0.0">
                """);
        for (int l = 0; l < layers.length; l++) {
            String[] layer = layers[l];
            sb.append("  <folder name=\"").append(layer[0]).append("\" id=\"id-f-").append(layer[0]).append("\" type=\"")
                    .append(layer[0]).append("\">\n");
            for (int i = l; i < elements; i += layers.length) {
                sb.append("    <element xsi:type=\"archimate:").append(layer[1 + i / 3 % 3]).append("\" name=\"Element ").append(i)
                        .append("\" id=\"id-e").append(i).append("\">\n      <documentation>Generated element ").append(i)
                        .append("</documentation>\n      <property key=\"owner\" value=\"team ").append(i % 7)
                        .append("\"/>\n    </element>\n");
            }
            sb.append("  </folder>\n");
        }
        sb.append("  <folder name=\"Relations\" id=\"id-f-rel\" type=\"relations\">\n");
        for (int i = 0; i < relations; i++) {
            sb.append("    <element xsi:type=\"archimate:").append(relTypes[i % relTypes.length]).append("\" id=\"id-r").append(i)
                    .append("\" source=\"id-e").append(i % elements).append("\" target=\"id-e").append((i * 7 + 1) % elements)
                    .append("\"/>\n");
        }
        sb.append("  </folder>\n  <folder name=\"Views\" id=\"id-f-views\" type=\"diagrams\">\n");
        for (int v = 0; v < views; v++) {
            sb.append("    <element xsi:type=\"archimate:ArchimateDiagramModel\" name=\"View ").append(v).append("\" id=\"id-v")
                    .append(v).append("\">\n");
            int first = v * objectsPerView;
            for (int k = 0; k < objectsPerView; k++) {
                int e = first + k;
                sb.append("      <child xsi:type=\"archimate:DiagramObject\" id=\"id-v").append(v).append("-o").append(e)
                        .append("\" archimateElement=\"id-e").append(e).append("\">\n        <bounds x=\"").append(k % 10 * 150)
                        .append("\" y=\"").append(k / 10 * 90).append("\" width=\"120\" height=\"55\"/>\n");
                // relationship i connects element i to (7i+1): draw it when both ends are on this view
                int target = (e * 7 + 1) % elements;
                if (e < relations && target >= first && target < first + objectsPerView) {
                    sb.append("        <sourceConnection xsi:type=\"archimate:Connection\" id=\"id-v").append(v).append("-c")
                            .append(e).append("\" source=\"id-v").append(v).append("-o").append(e).append("\" target=\"id-v")
                            .append(v).append("-o").append(target).append("\" archimateRelationship=\"id-r").append(e)
                            .append("\"/>\n");
                }
                sb.append("      </child>\n");
            }
            sb.append("    </element>\n");
        }
        sb.append("  </folder>\n</archimate:model>\n");
        return sb.toString();
    }
}
