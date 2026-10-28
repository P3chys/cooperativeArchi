package io.github.p3chys.cooperativearchi.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IIdentifier;

import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.model.SplitLayout;

/**
 * Makes an open model equal to a freshly loaded version of it by changing only what differs, so editors,
 * selection and the user's undo stack survive incoming changes (no close and reopen).
 *
 * <p>Objects with an id keep their Java identity (moved, not recreated); objects without one (bounds, properties,
 * bendpoints...) are matched by position. Changes are made directly on EMF, outside the command stack, and Archi's
 * derived caches (relationship ends, diagram connection targets) are kept consistent. Pure EMF, no UI.
 */
final class ModelReconciler {

    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    /** Live objects by id. */
    private final Map<String, EObject> live = new HashMap<>();
    /** Incoming object to the live object that now represents it. */
    private final Map<EObject, EObject> counterpart = new LinkedHashMap<>();

    private ModelReconciler() {
    }

    static void reconcile(IArchimateModel target, IArchimateModel incoming) {
        if (!Objects.equals(target.getId(), incoming.getId())) {
            throw new IllegalArgumentException("Not the same model: " + target.getId() + " / " + incoming.getId());
        }
        new ModelReconciler().run(target, incoming);
    }

    private void run(IArchimateModel target, IArchimateModel incoming) {
        index(target, live);
        Map<String, EObject> in = new HashMap<>();
        index(incoming, in);
        // objects that are gone (or changed type) leave Archi's caches; their containers drop them below
        for (Iterator<Map.Entry<String, EObject>> it = live.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, EObject> e = it.next();
            EObject other = in.get(e.getKey());
            if (other == null || other.eClass() != e.getValue().eClass()) {
                if (e.getValue() instanceof IArchimateRelationship r) {
                    r.disconnect();
                } else if (e.getValue() instanceof IDiagramModelConnection c) {
                    c.disconnect();
                }
                it.remove();
            }
        }
        counterpart.put(incoming, target);
        syncContents(incoming, target);
        // references last, when every object they can point to exists
        counterpart.forEach(this::syncReferences);
    }

    private static void index(EObject root, Map<String, EObject> out) {
        if (root instanceof IIdentifier i && i.getId() != null) {
            out.put(i.getId(), root);
        }
        for (Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IIdentifier i && i.getId() != null) {
                out.put(i.getId(), i);
            }
        }
    }

    /** Attributes and contained objects, recursively. New sub trees are complete before they are attached. */
    @SuppressWarnings("unchecked")
    private void syncContents(EObject src, EObject dst) {
        for (EAttribute a : src.eClass().getEAllAttributes()) {
            if (!skip(a) && a != P.getIdentifier_Id()) {
                copy(src, dst, a);
            }
        }
        for (EReference r : src.eClass().getEAllContainments()) {
            if (skip(r)) {
                continue;
            }
            if (r.isMany()) {
                List<EObject> from = (List<EObject>) src.eGet(r);
                EList<EObject> to = (EList<EObject>) dst.eGet(r);
                List<EObject> wanted = new ArrayList<>(from.size());
                for (int i = 0; i < from.size(); i++) {
                    EObject m = match(from.get(i), i < to.size() ? to.get(i) : null);
                    syncContents(from.get(i), m);
                    wanted.add(m);
                }
                if (!wanted.equals(to)) {
                    ECollections.setEList(to, wanted);
                }
            } else {
                EObject s = (EObject) src.eGet(r);
                if (s == null) {
                    if (dst.eIsSet(r)) {
                        dst.eUnset(r);
                    }
                    continue;
                }
                EObject m = match(s, (EObject) dst.eGet(r));
                syncContents(s, m);
                if (dst.eGet(r) != m) {
                    dst.eSet(r, m);
                }
            }
        }
    }

    /** The live object for an incoming one: same id, else the object at the same position, else a new one. */
    private EObject match(EObject src, EObject atSamePosition) {
        String id = src instanceof IIdentifier i ? i.getId() : null;
        EObject m;
        if (id != null) {
            m = live.get(id);
            if (m == null) {
                m = EcoreUtil.create(src.eClass());
                ((IIdentifier) m).setId(id);
                live.put(id, m);
            }
        } else if (atSamePosition != null && !(atSamePosition instanceof IIdentifier)
                && atSamePosition.eClass() == src.eClass()) {
            m = atSamePosition;
        } else {
            m = EcoreUtil.create(src.eClass());
        }
        counterpart.put(src, m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void syncReferences(EObject src, EObject dst) {
        for (EReference r : src.eClass().getEAllReferences()) {
            if (r.isContainment() || r.isContainer() || skip(r) || r == P.getConnectable_TargetConnections()
                    || r == P.getDiagramModelConnection_Source() || r == P.getDiagramModelConnection_Target()) {
                continue;
            }
            if (r.isMany()) {
                List<EObject> wanted = new ArrayList<>();
                for (EObject o : (List<EObject>) src.eGet(r)) {
                    wanted.add(toLive(o));
                }
                EList<EObject> current = (EList<EObject>) dst.eGet(r);
                if (!wanted.equals(current)) {
                    ECollections.setEList(current, wanted);
                }
            } else {
                // relationship ends and diagram -> concept links go through Archi's setters, which update its caches
                EObject wanted = toLive((EObject) src.eGet(r));
                if (dst.eGet(r) != wanted) {
                    dst.eSet(r, wanted);
                }
            }
        }
        if (src instanceof IDiagramModelConnection s && dst instanceof IDiagramModelConnection c) {
            IConnectable source = (IConnectable) toLive(s.getSource());
            IConnectable target = (IConnectable) toLive(s.getTarget());
            if (c.getSource() != source || c.getTarget() != target || !target.getTargetConnections().contains(c)) {
                // not connect(): that re-adds the connection at the end of its source's (ordered) list
                if (c.getTarget() != null) {
                    c.getTarget().getTargetConnections().remove(c);
                }
                c.setSource(source);
                c.setTarget(target);
                target.getTargetConnections().add(c);
            }
        }
    }

    private EObject toLive(EObject incoming) {
        if (incoming == null) {
            return null;
        }
        EObject m = counterpart.get(incoming);
        if (m == null && incoming instanceof IIdentifier i) {
            m = live.get(i.getId());
        }
        if (m == null) {
            throw new IllegalStateException("Reference to an object outside the model: " + incoming);
        }
        return m;
    }

    private static void copy(EObject src, EObject dst, EStructuralFeature f) {
        boolean set = src.eIsSet(f);
        if (set == dst.eIsSet(f) && Objects.equals(src.eGet(f), dst.eGet(f))) {
            return;
        }
        if (set) {
            dst.eSet(f, src.eGet(f));
        } else {
            dst.eUnset(f);
        }
    }

    private static boolean skip(EStructuralFeature f) {
        return f.isTransient() || f.isDerived() || !f.isChangeable();
    }

    // ---------------------------------------------------------------- verification

    /** The model as Archi would write it (without images, which live in the archive manager). */
    static byte[] serialize(IArchimateModel model) throws IOException {
        Resource r = model.eResource();
        if (r == null) {
            throw new IOException("Model has no resource");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.save(out, null);
        return out.toByteArray();
    }

    /**
     * The split files in which two .archimate contents differ, ignoring images and formatting; empty if they
     * are the same model.
     */
    static Set<String> differences(byte[] a, byte[] b) throws IOException {
        Map<String, byte[]> fa = SplitLayout.write(ModelGraph.read(a));
        Map<String, byte[]> fb = SplitLayout.write(ModelGraph.read(b));
        Set<String> paths = new TreeSet<>(fa.keySet());
        paths.addAll(fb.keySet());
        paths.removeIf(p -> p.equals(SplitLayout.SNAPSHOT_FILE) || p.startsWith(SplitLayout.IMAGES_DIR)
                || Arrays.equals(fa.get(p), fb.get(p)));
        return paths;
    }
}
