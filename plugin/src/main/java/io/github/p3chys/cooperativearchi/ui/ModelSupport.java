package io.github.p3chys.cooperativearchi.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IDiagramModel;

import io.github.p3chys.cooperativearchi.git.SharedModel;

/** Glue between Archi's open models and shared model workspaces. */
final class ModelSupport {

    private ModelSupport() {
    }

    static boolean isShared(IArchimateModel model) {
        return model != null && model.getFile() != null && SharedModel.isShared(model.getFile().toPath());
    }

    static List<IArchimateModel> sharedModels() {
        List<IArchimateModel> list = new ArrayList<>();
        for (IArchimateModel m : IEditorModelManager.INSTANCE.getModels()) {
            if (isShared(m)) {
                list.add(m);
            }
        }
        return list;
    }

    static boolean isDirty(IArchimateModel model) {
        return IEditorModelManager.INSTANCE.isModelDirty(model);
    }

    /** The model the user is working on: selection, active editor, or the only shared model. */
    static IArchimateModel activeModel(ExecutionEvent event) {
        IWorkbenchPart part = event == null ? null : HandlerUtil.getActivePart(event);
        ISelection selection = event == null ? null : HandlerUtil.getCurrentSelection(event);
        return activeModel(part, selection);
    }

    static IArchimateModel activeModel(IWorkbenchPart part, ISelection selection) {
        if (selection instanceof IStructuredSelection s && s.getFirstElement() instanceof IArchimateModelObject o
                && o.getArchimateModel() != null) {
            return o.getArchimateModel();
        }
        if (part != null) {
            IArchimateModel m = part.getAdapter(IArchimateModel.class);
            if (m != null) {
                return m;
            }
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null && window.getActivePage() != null
                && window.getActivePage().getActiveEditor() instanceof IDiagramModelEditor editor) {
            return editor.getModel().getArchimateModel();
        }
        List<IArchimateModel> shared = sharedModels();
        return shared.size() == 1 ? shared.get(0) : null;
    }

    static IArchimateModel open(Path file) {
        for (IArchimateModel m : IEditorModelManager.INSTANCE.getModels()) {
            if (m.getFile() != null && m.getFile().toPath().toAbsolutePath().equals(file.toAbsolutePath())) {
                return m;
            }
        }
        return IEditorModelManager.INSTANCE.openModel(file.toFile());
    }

    /** Re-opens the model from disk, re-opening the views that were open. Returns the new model instance. */
    static IArchimateModel reload(IArchimateModel model) throws IOException {
        File file = model.getFile();
        List<String> openViews = new ArrayList<>();
        String activeView = null;
        IWorkbenchPage page = activePage();
        if (page != null) {
            IEditorPart active = page.getActiveEditor();
            for (IEditorReference ref : page.getEditorReferences()) {
                if (ref.getEditor(false) instanceof IDiagramModelEditor e && e.getModel().getArchimateModel() == model) {
                    openViews.add(e.getModel().getId());
                    if (e == active) {
                        activeView = e.getModel().getId();
                    }
                }
            }
        }
        if (!IEditorModelManager.INSTANCE.closeModel(model)) {
            return null;
        }
        IArchimateModel reloaded = IEditorModelManager.INSTANCE.openModel(file);
        if (reloaded == null) {
            return null;
        }
        IDiagramModel toActivate = null;
        for (IDiagramModel dm : reloaded.getDiagramModels()) {
            if (openViews.contains(dm.getId())) {
                EditorManager.openDiagramEditor(dm, false);
                if (dm.getId().equals(activeView)) {
                    toActivate = dm;
                }
            }
        }
        if (toActivate != null) {
            EditorManager.openDiagramEditor(toActivate, true);
        }
        return reloaded;
    }

    static IWorkbenchPage activePage() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getActivePage();
    }

    static Shell shell() {
        Display d = Display.getDefault();
        Shell s = d.getActiveShell();
        if (s == null) {
            IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            s = w == null ? null : w.getShell();
        }
        return s;
    }

    /** Small popup in the corner that disappears by itself. */
    static void notify(String title, String text) {
        Display d = Display.getDefault();
        d.asyncExec(() -> Compat.notify(d, title, text));
    }
}
