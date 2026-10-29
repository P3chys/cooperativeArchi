package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.ui.PartInitException;

import com.archimatetool.model.IArchimateModel;

/** Command handlers referenced from plugin.xml. */
public final class Handlers {

    private Handlers() {
    }

    public static class Sync extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            SyncRunner.sync(ModelSupport.activeModel(event), SyncRunner.Mode.INTERACTIVE, null);
            return null;
        }
    }

    public static class Pull extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            SyncRunner.pull(ModelSupport.activeModel(event));
            return null;
        }
    }

    public static class Push extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            SyncRunner.push(ModelSupport.activeModel(event));
            return null;
        }
    }

    public static class SyncAll extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            var models = ModelSupport.sharedModels();
            if (models.isEmpty()) {
                MessageDialog.openInformation(ModelSupport.shell(), "Sync", "No shared model is open.");
            }
            for (IArchimateModel m : models) {
                if (!SyncRunner.sync(m, SyncRunner.Mode.INTERACTIVE, null).ok()) {
                    break;
                }
            }
            return null;
        }
    }

    public static class Share extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            ShareDialog.share(ModelSupport.activeModel(event));
            return null;
        }
    }

    public static class Clone extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            CloneDialog.run();
            return null;
        }
    }

    public static class Branches extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            BranchesDialog.open(ModelSupport.activeModel(event));
            return null;
        }
    }

    public static class History extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            try {
                ModelSupport.activePage().showView(HistoryView.ID);
            } catch (PartInitException e) {
                Activator.log("Cannot open history", e);
            }
            return null;
        }
    }

    public static class SignIn extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            TokenDialog.ask(ModelSupport.shell());
            return null;
        }
    }

    public static class OpenFolder extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            IArchimateModel m = ModelSupport.activeModel(event);
            String dir = m != null && m.getFile() != null ? m.getFile().getParent() : Prefs.folder().toString();
            Program.launch(dir);
            return null;
        }
    }
}
