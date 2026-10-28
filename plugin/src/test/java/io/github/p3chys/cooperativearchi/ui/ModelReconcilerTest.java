package io.github.p3chys.cooperativearchi.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.junit.jupiter.api.Test;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.util.ArchimateResourceFactory;

/** Runs headless on Archi's model bundle: every case must end equal to "after", with ids and caches intact. */
class ModelReconcilerTest {

    private static final String BASE = fixture();

    private static final String ACTOR_DO = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-actor\" archimateElement=\"id-actor\">\n"
            + "        <bounds x=\"24\" y=\"24\" width=\"120\" height=\"55\"/>\n"
            + "        <sourceConnection xsi:type=\"archimate:Connection\" id=\"id-c1\" source=\"id-do-actor\" target=\"id-do-role\" archimateRelationship=\"id-r1\"/>\n"
            + "      </child>\n      ";
    private static final String ROLE_DO = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-role\" archimateElement=\"id-role\">\n"
            + "        <bounds x=\"240\" y=\"24\" width=\"120\" height=\"55\"/>\n"
            + "      </child>\n      ";
    private static final String CRM_DO = "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-crm\" archimateElement=\"id-crm\" type=\"1\">\n"
            + "          <bounds x=\"12\" y=\"36\" width=\"120\" height=\"55\"/>\n"
            + "        </child>\n        ";

    @Test
    void identicalModelChangesNothing() {
        IArchimateModel live = load(BASE);
        AtomicInteger notifications = countNotifications(live);
        ModelReconciler.reconcile(live, load(BASE));
        assertThat(notifications).hasValue(0);
    }

    @Test
    void rename() {
        assertReconciles("name=\"Customer\"", "name=\"Client\"");
    }

    @Test
    void moveAndResizeInView() {
        assertReconciles("<bounds x=\"24\" y=\"24\" width=\"120\"", "<bounds x=\"300\" y=\"60\" width=\"200\"");
    }

    @Test
    void documentationAndProperties() {
        assertReconciles("<documentation>A customer</documentation>", "",
                "<property key=\"owner\" value=\"sales\"/>", "<property key=\"owner\" value=\"it\"/><property key=\"x\" value=\"y\"/>",
                "name=\"CRM\" id=\"id-crm\"/>", "name=\"CRM\" id=\"id-crm\"><property key=\"k\"/></element>");
    }

    @Test
    void addElementRelationshipAndDiagramObjects() {
        assertReconciles(
                "<folder name=\"Strategy\" id=\"id-f-strat\" type=\"strategy\"/>",
                "<folder name=\"Strategy\" id=\"id-f-strat\" type=\"strategy\"><element xsi:type=\"archimate:Capability\" name=\"Sell\" id=\"id-cap\"/></folder>",
                "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\"",
                "<element xsi:type=\"archimate:AssociationRelationship\" id=\"id-r9\" source=\"id-cap\" target=\"id-actor\"/>"
                        + "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\"",
                "<child xsi:type=\"archimate:DiagramModelReference\"",
                "<child xsi:type=\"archimate:DiagramObject\" id=\"id-do-cap\" archimateElement=\"id-cap\">"
                        + "<bounds x=\"600\" y=\"200\" width=\"120\" height=\"55\"/>"
                        + "<sourceConnection xsi:type=\"archimate:Connection\" id=\"id-c9\" source=\"id-do-cap\" target=\"id-do-actor\" archimateRelationship=\"id-r9\"/>"
                        + "</child><child xsi:type=\"archimate:DiagramModelReference\"");
    }

    @Test
    void deleteElementWithRelationshipAndDiagramObject() {
        assertReconciles("<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>", "",
                "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\" source=\"id-crm\" target=\"id-proc\"/>", "",
                CRM_DO, "");
    }

    @Test
    void deleteConnectionOnly() {
        assertReconciles("<sourceConnection xsi:type=\"archimate:Connection\" id=\"id-c1\" source=\"id-do-actor\" target=\"id-do-role\" archimateRelationship=\"id-r1\"/>", "");
    }

    @Test
    void reconnectRelationshipAndConnection() {
        assertReconciles("id=\"id-r1\" source=\"id-actor\" target=\"id-role\"", "id=\"id-r1\" source=\"id-actor\" target=\"id-crm\"",
                "target=\"id-do-role\" archimateRelationship=\"id-r1\"", "target=\"id-do-crm\" archimateRelationship=\"id-r1\"");
    }

    @Test
    void reorderViewChildren() {
        assertReconciles(ACTOR_DO + ROLE_DO, ROLE_DO + ACTOR_DO);
    }

    @Test
    void moveDiagramObjectIntoGroupAndOut() {
        assertReconciles(ROLE_DO, "", "<child xsi:type=\"archimate:Note\" id=\"id-note\">", ROLE_DO + "<child xsi:type=\"archimate:Note\" id=\"id-note\">",
                CRM_DO, "", "<child xsi:type=\"archimate:DiagramModelReference\"", CRM_DO + "<child xsi:type=\"archimate:DiagramModelReference\"");
    }

    @Test
    void moveFolderAndElementBetweenFolders() {
        String sub = "<folder name=\"Sub\" id=\"id-f-sub\">\n      <element xsi:type=\"archimate:BusinessRole\" name=\"Insurant\" id=\"id-role\"/>\n    </folder>";
        assertReconciles(sub, "",
                "<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>",
                "<element xsi:type=\"archimate:ApplicationComponent\" name=\"CRM\" id=\"id-crm\"/>" + sub,
                "<element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/>", "",
                "<folder name=\"Other\" id=\"id-f-other\" type=\"other\"/>",
                "<folder name=\"Other\" id=\"id-f-other\" type=\"other\"><element xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\" id=\"id-proc\"/></folder>");
    }

    @Test
    void changedElementType() {
        assertReconciles("xsi:type=\"archimate:BusinessProcess\" name=\"Handle Claim\"", "xsi:type=\"archimate:BusinessFunction\" name=\"Handle Claim\"");
    }

    @Test
    void profilesAndViewReference() {
        assertReconciles("name=\"Customer\" id=\"id-actor\"", "name=\"Customer\" id=\"id-actor\" profiles=\"id-prof\"",
                "model=\"id-view2\"", "model=\"id-view\"");
    }

    @Test
    void everythingAtOnce() {
        assertReconciles("name=\"Customer\"", "name=\"Client\"", ACTOR_DO + ROLE_DO, ROLE_DO + ACTOR_DO,
                "<element xsi:type=\"archimate:ServingRelationship\" id=\"id-r2\" source=\"id-crm\" target=\"id-proc\"/>", "",
                "<content>Hello note</content>", "<content>Bye</content>", "<purpose>Test purpose</purpose>", "");
    }

    @Test
    void largeModelWithScatteredEdits() {
        String before = generated(2000, false);
        String after = generated(2000, true);
        IArchimateModel live = load(before);
        long t = System.nanoTime();
        ModelReconciler.reconcile(live, load(after));
        System.out.printf("reconcile 2000 elements: %d ms%n", (System.nanoTime() - t) / 1_000_000);
        assertModelReconciles(before, after);
    }

    /**
     * n elements in a chain of relationships, a view showing the first 300 with connections. "after": every 40th
     * renamed, every 25th diagram object moved, 20 elements (and their relationships) deleted, 20 added, one reordered.
     */
    private static String generated(int n, boolean after) {
        StringBuilder els = new StringBuilder(), rels = new StringBuilder(), view = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (!(after && i >= 1000 && i < 1020)) {
                String name = after && i % 40 == 0 ? "Renamed " + i : "E" + i;
                els.append("<element xsi:type=\"archimate:BusinessActor\" name=\"%s\" id=\"e%d\"/>%n".formatted(name, i));
            }
            if (!(after && i >= 999 && i < 1020) && i + 1 < n) {
                rels.append("<element xsi:type=\"archimate:AssociationRelationship\" id=\"r%d\" source=\"e%d\" target=\"e%d\"/>%n"
                        .formatted(i, i, i + 1));
            }
        }
        if (after) {
            for (int k = 0; k < 20; k++) {
                els.append("<element xsi:type=\"archimate:BusinessRole\" name=\"New%d\" id=\"n%d\"/>%n".formatted(k, k));
                rels.append("<element xsi:type=\"archimate:AssignmentRelationship\" id=\"rn%d\" source=\"e%d\" target=\"n%d\"/>%n"
                        .formatted(k, k, k));
            }
        }
        StringBuilder moved = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            int x = (i % 20) * 150 + (after && i % 25 == 3 ? 40 : 0);
            String connection = i + 1 < 300 ? ("<sourceConnection xsi:type=\"archimate:Connection\" id=\"k%d\" source=\"d%d\" target=\"d%d\""
                    + " archimateRelationship=\"r%d\"/>").formatted(i, i, i + 1, i) : "";
            String d = ("<child xsi:type=\"archimate:DiagramObject\" id=\"d%d\" archimateElement=\"e%d\">"
                    + "<bounds x=\"%d\" y=\"%d\" width=\"120\" height=\"55\"/>%s</child>%n").formatted(i, i, x, (i / 20) * 80, connection);
            (after && i == 5 ? moved : view).append(d);
        }
        view.append(moved);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <archimate:model xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:archimate="http://www.archimatetool.com/archimate" name="Big" id="id-big" version="5.0.0">
                <folder name="Business" id="f-b" type="business">
                %s</folder>
                <folder name="Relations" id="f-r" type="relations">
                %s</folder>
                <folder name="Views" id="f-v" type="diagrams"><element xsi:type="archimate:ArchimateDiagramModel" name="V" id="v1">
                %s</element></folder>
                </archimate:model>
                """.formatted(els, rels, view);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertReconciles(String... pairs) {
        assertModelReconciles(BASE, edit(BASE, pairs));
    }

    private static void assertModelReconciles(String beforeXml, String after) {
        IArchimateModel live = load(beforeXml);
        Map<String, EObject> before = ids(live);

        ModelReconciler.reconcile(live, load(after));

        try {
            byte[] result = ModelReconciler.serialize(live);
            Set<String> diff = ModelReconciler.differences(result, ModelReconciler.serialize(load(after)));
            assertThat(diff).as("files differing from the incoming model").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, EObject> now = ids(live);
        assertThat(now.keySet()).isEqualTo(ids(load(after)).keySet());
        for (Map.Entry<String, EObject> e : now.entrySet()) {
            EObject old = before.get(e.getKey());
            if (old != null && old.eClass() == e.getValue().eClass()) {
                assertThat(e.getValue()).as("identity of " + e.getKey()).isSameAs(old);
            }
            assertThat(caches(e.getValue())).as("caches of " + e.getKey()).isEqualTo(expectedCaches(live, e.getValue()));
        }
    }

    /** Archi's derived lists of an object, as ids. */
    private static List<Set<String>> caches(EObject o) {
        if (o instanceof IArchimateConcept c) {
            return List.of(idSet(c.getSourceRelationships()), idSet(c.getTargetRelationships()),
                    idSet(c.getReferencingDiagramComponents()));
        }
        if (o instanceof IConnectable c) {
            return List.of(idSet(c.getTargetConnections()));
        }
        return List.of();
    }

    /** The derived lists recomputed from the model's own references. */
    private static List<Set<String>> expectedCaches(IArchimateModel model, EObject o) {
        Set<String> src = new TreeSet<>(), tgt = new TreeSet<>(), refs = new TreeSet<>(), conns = new TreeSet<>();
        for (EObject x : ids(model).values()) {
            if (x instanceof IArchimateRelationship r) {
                if (r.getSource() == o) {
                    src.add(r.getId());
                }
                if (r.getTarget() == o) {
                    tgt.add(r.getId());
                }
            }
            if (x instanceof IDiagramModelArchimateComponent dc && dc.getArchimateConcept() == o) {
                refs.add(dc.getId());
            }
            if (x instanceof IDiagramModelConnection c && c.getTarget() == o) {
                conns.add(c.getId());
            }
        }
        if (o instanceof IArchimateConcept) {
            return List.of(src, tgt, refs);
        }
        return o instanceof IConnectable ? List.of(conns) : List.of();
    }

    private static Set<String> idSet(List<? extends IIdentifier> objects) {
        Set<String> s = new TreeSet<>();
        objects.forEach(o -> s.add(o.getId()));
        return s;
    }

    private static Map<String, EObject> ids(EObject root) {
        Map<String, EObject> m = new HashMap<>();
        m.put(((IIdentifier) root).getId(), root);
        for (Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IIdentifier i) {
                m.put(i.getId(), i);
            }
        }
        return m;
    }

    private static AtomicInteger countNotifications(IArchimateModel model) {
        AtomicInteger n = new AtomicInteger();
        model.eAdapters().add(new EContentAdapter() {
            @Override
            public void notifyChanged(Notification notification) {
                super.notifyChanged(notification);
                if (!notification.isTouch()) {
                    n.incrementAndGet();
                }
            }
        });
        return n;
    }

    private static IArchimateModel load(String xml) {
        try {
            Resource r = ArchimateResourceFactory.createNewResource(URI.createURI("mem:/" + System.nanoTime() + ".archimate"));
            r.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), null);
            IArchimateModel m = (IArchimateModel) r.getContents().get(0);
            // as Archi does when it opens a model: target connections are derived, not loaded
            ids(m).values().forEach(o -> {
                if (o instanceof IDiagramModelConnection c) {
                    c.reconnect();
                }
            });
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String edit(String xml, String... pairs) {
        String out = xml;
        for (int i = 0; i < pairs.length; i += 2) {
            assertThat(out).as("fixture").contains(pairs[i]);
            out = out.replace(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static String fixture() {
        try {
            return Files.readString(Path.of("../core/src/test/resources/test-model.archimate")).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
