package io.github.p3chys.cooperativearchi;

import static io.github.p3chys.cooperativearchi.TestModels.base;
import static io.github.p3chys.cooperativearchi.TestModels.edited;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.p3chys.cooperativearchi.merge.ModelMerger;
import io.github.p3chys.cooperativearchi.merge.ModelMerger.MergeSession;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.SplitLayout;

/**
 * Feeds merged models to the real Archi (command line) and checks that Archi opens them and that saving
 * them from Archi does not produce changes. Skipped when no Archi installation is configured (-ParchiHome).
 */
class ArchiValidationTest {

    private static Path archi;

    @TempDir
    Path tmp;

    @BeforeAll
    static void findArchi() {
        String home = System.getProperty("archiHome", "");
        Path dir = home.isBlank() ? null : Path.of(home);
        for (String exe : List.of("Archi.exe", "Archi", "../MacOS/Archi")) {
            if (dir != null && Files.isRegularFile(dir.resolve(exe))) {
                archi = dir.resolve(exe);
            }
        }
        Assumptions.assumeTrue(archi != null, "Archi not found at archiHome=" + home);
    }

    @Test
    void mergedModelWithRestoredElementOpensInArchi() throws Exception {
        String rel = "<element xsi:type=\"archimate:FlowRelationship\" id=\"id-r3\" source=\"id-crm\" target=\"id-actor\"/>";
        String anchor = "<element xsi:type=\"archimate:ServingRelationship\"";
        String newObj = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-new1\" archimateElement=\"id-proc\">"
                + "<bounds x=\"600\" y=\"200\" width=\"120\" height=\"55\"/>"
                + "<sourceConnection xsi:type=\"archimate:Connection\" id=\"id-c9\" source=\"id-new1\" target=\"id-do-crm\" archimateRelationship=\"id-r2\"/>"
                + "</child>";
        String viewAnchor = "<child xsi:type=\"archimate:DiagramModelReference\"";
        ModelGraph mine = edited(anchor, rel + anchor, viewAnchor, newObj + viewAnchor, "name=\"Customer\"", "name=\"Client\"");
        String doCrm = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-crm\" archimateElement=\"id-crm\" type=\"1\">\n"
                + "          <bounds x=\"12\" y=\"36\" width=\"120\" height=\"55\"/>\n        </child>";
        ModelGraph theirs = edited("<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>", "",
                "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\" source=\"id-crm\" target=\"id-proc\"/>", "",
                doCrm, "", "name=\"Group\"", "name=\"Their group\"");

        MergeSession s = ModelMerger.prepare(base(), mine, theirs);
        assertThat(s.conflicts()).isEmpty();
        assertOpensAndIsStable(s.finish());
    }

    @Test
    void mergedViewEditsOpenInArchi() throws Exception {
        String bounds = "<bounds x=\"240\" y=\"24\" width=\"120\" height=\"55\"/>";
        String note = "<child xsi:type=\"archimate:Note\" id=\"id-n2\"><bounds x=\"10\" y=\"400\" width=\"100\" height=\"50\"/><content>Bob</content></child>";
        String anchor = "<child xsi:type=\"archimate:DiagramModelReference\"";
        ModelGraph mine = edited(bounds, "<bounds x=\"300\" y=\"24\" width=\"120\" height=\"55\"/>");
        ModelGraph theirs = edited(anchor, note + anchor, "<property key=\"owner\" value=\"sales\"/>",
                "<property key=\"owner\" value=\"sales\"/><property key=\"x\" value=\"y\"/>");

        assertOpensAndIsStable(ModelMerger.mergeClean(base(), mine, theirs));
    }

    private void assertOpensAndIsStable(ModelGraph merged) throws Exception {
        Map<String, byte[]> files = SplitLayout.write(merged);
        Path in = tmp.resolve("merged.archimate");
        Path out = tmp.resolve("saved-by-archi.archimate");
        Files.write(in, files.get(SplitLayout.SNAPSHOT_FILE));

        String log = runArchi("--loadModel", in.toString(), "--saveModel", out.toString());
        assertThat(log).contains("Loaded model").contains("Saved model").doesNotContain("Exception");

        Map<String, byte[]> again = SplitLayout.write(ModelGraph.read(Files.readAllBytes(out)));
        assertThat(again.keySet()).isEqualTo(files.keySet());
        for (String path : files.keySet()) {
            assertThat(new String(again.get(path), StandardCharsets.UTF_8)).as(path)
                    .isEqualTo(new String(files.get(path), StandardCharsets.UTF_8));
        }
    }

    private static String runArchi(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(archi.toString(), "-application", "com.archimatetool.commandline.app",
                "-consoleLog", "-nosplash"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(3, TimeUnit.MINUTES)).isTrue();
        return out;
    }
}
