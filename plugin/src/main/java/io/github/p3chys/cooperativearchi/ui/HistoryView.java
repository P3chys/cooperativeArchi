package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.part.ViewPart;

import com.archimatetool.model.IArchimateModel;

import io.github.p3chys.cooperativearchi.git.HistoryEntry;
import io.github.p3chys.cooperativearchi.git.HistoryGroup;
import io.github.p3chys.cooperativearchi.git.SharedModel;

/**
 * Versions of the selected shared model: who changed what and when; open or restore old versions. Runs of versions by
 * one analyst close together in time are one expandable row ({@link HistoryGroup}); actions on it use its newest version.
 */
public class HistoryView extends ViewPart {

    public static final String ID = "io.github.p3chys.cooperativearchi.historyView";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());

    private Label header;
    private TreeViewer viewer;
    private IArchimateModel model;
    private final Runnable refresher = () -> getSite().getShell().getDisplay().asyncExec(this::refresh);
    private final ISelectionListener selectionListener = (part, selection) -> {
        IArchimateModel m = ModelSupport.activeModel(part, selection);
        if (m != null && m != model && ModelSupport.isShared(m)) {
            model = m;
            refresh();
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        header = new Label(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);

        Composite tableBox = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tableBox);
        TreeColumnLayout layout = new TreeColumnLayout();
        tableBox.setLayout(layout);
        viewer = new TreeViewer(tableBox, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        viewer.setContentProvider(new Rows());
        ColumnViewerToolTipSupport.enableFor(viewer);

        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
        Color grey = parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
        column(layout, "When", 18, e -> WHEN.format(e.time()), HistoryView::when, bold, grey);
        column(layout, "Who", 14, HistoryEntry::author, HistoryGroup::author, bold, grey);
        column(layout, "What", 58, e -> (e.current() ? "▶ " : "") + e.message().lines().findFirst().orElse("")
                + (e.uploaded() ? "" : "   [not uploaded yet]"), g -> (g.current() ? "▶ " : "") + "(" + g.size()
                        + " versions) " + g.summary(3) + (g.uploaded() ? "" : "   [not uploaded yet]"), bold, grey);
        column(layout, "Version", 10, HistoryEntry::shortId, g -> g.newest().shortId(), bold, grey);

        Action open = new Action("Open this version as a copy") {
            @Override
            public void run() {
                openCopy();
            }
        };
        Action restore = new Action("Restore this version...") {
            @Override
            public void run() {
                restore();
            }
        };
        Action refresh = new Action("Refresh", Activator.icon("sync.png")) {
            @Override
            public void run() {
                refresh();
            }
        };
        MenuManager menu = new MenuManager();
        menu.add(open);
        menu.add(restore);
        viewer.getTree().setMenu(menu.createContextMenu(viewer.getTree()));
        viewer.addDoubleClickListener(e -> openCopy());
        getViewSite().getActionBars().getToolBarManager().add(refresh);

        getSite().getPage().addPostSelectionListener(selectionListener);
        SyncRunner.LISTENERS.add(refresher);
        model = ModelSupport.activeModel(getSite().getPage().getActivePart(), getSite().getPage().getSelection());
        refresh();
    }

    private void column(TreeColumnLayout layout, String title, int weight, Function<HistoryEntry, String> entryText,
            Function<HistoryGroup, String> groupText, Font bold, Color grey) {
        TreeViewerColumn col = new TreeViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        layout.setColumnData(col.getColumn(), new ColumnWeightData(weight, 60));
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof HistoryGroup g ? groupText.apply(g) : entryText.apply((HistoryEntry) element);
            }

            @Override
            public String getToolTipText(Object element) {
                if (element instanceof HistoryGroup g) {
                    StringBuilder sb = new StringBuilder(g.size() + " versions by " + g.author() + " <" + g.email() + ">, "
                            + when(g) + "\n");
                    g.entries().stream().filter(e -> !e.merge()).limit(15).forEach(e -> sb.append("\n")
                            .append(TIME.format(e.time())).append("  ").append(e.message().lines().findFirst().orElse("")));
                    return sb.toString();
                }
                HistoryEntry e = (HistoryEntry) element;
                return e.message() + "\n\n" + e.author() + " <" + e.email() + ">, " + WHEN.format(e.time()) + "\nVersion "
                        + e.id().name();
            }

            @Override
            public Font getFont(Object element) {
                boolean current = element instanceof HistoryGroup g ? g.current() : ((HistoryEntry) element).current();
                return current ? bold : null;
            }

            @Override
            public Color getForeground(Object element) {
                boolean merge = element instanceof HistoryGroup g ? g.merge() : ((HistoryEntry) element).merge();
                return merge ? grey : null;
            }
        });
    }

    /** "from – to" of a group; the end without its date when on the same day. */
    private static String when(HistoryGroup g) {
        Instant from = g.oldest().time();
        Instant to = g.newest().time();
        return WHEN.format(from) + " – " + (DAY.format(from).equals(DAY.format(to)) ? TIME : WHEN).format(to);
    }

    /** Roots are groups, or the version itself for a one-version group (shown as before grouping). */
    private static final class Rows implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            return ((List<?>) input).stream().map(HistoryView::row).toArray();
        }

        @Override
        public Object[] getChildren(Object parent) {
            return parent instanceof HistoryGroup g ? g.entries().toArray() : new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof HistoryGroup;
        }
    }

    private static Object row(Object group) {
        return group instanceof HistoryGroup g && g.size() == 1 ? g.newest() : group;
    }

    void refresh() {
        if (viewer == null || viewer.getTree().isDisposed()) {
            return;
        }
        if (model == null || model.getFile() == null || !ModelSupport.isShared(model)) {
            header.setText("Select a shared model to see its history.");
            viewer.setInput(List.of());
            return;
        }
        try (SharedModel sm = SharedModel.open(model.getFile().toPath())) {
            header.setText("'" + model.getName() + "'  —  branch " + sm.branch() + "  —  ▶ = the version you have");
            setRows(HistoryGroup.of(sm.history(300), HistoryGroup.GAP));
        } catch (IOException e) {
            header.setText("Cannot read history: " + e.getMessage());
        }
        header.getParent().layout();
    }

    /** New rows, keeping expanded groups (by oldest version, which stays put while a group grows) and the selection. */
    private void setRows(List<HistoryGroup> groups) {
        Set<String> expanded = new HashSet<>();
        for (Object o : viewer.getExpandedElements()) {
            expanded.add(key(o));
        }
        Object sel = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
        String selKey = sel == null ? null : key(sel);
        viewer.setInput(groups);
        Object reselect = null;
        for (HistoryGroup g : groups) {
            Object row = row(g);
            if (expanded.contains(key(row))) {
                viewer.setExpandedState(row, true);
            }
            for (Object o : row == g ? List.<Object>copyOf(g.entries()) : List.of()) {
                if (key(o).equals(selKey)) {
                    reselect = o;
                }
            }
            if (key(row).equals(selKey)) {
                reselect = row;
            }
        }
        if (reselect != null) {
            viewer.setSelection(new StructuredSelection(reselect), true);
        }
    }

    private static String key(Object row) {
        return row instanceof HistoryGroup g ? "group " + g.oldest().id().name() : ((HistoryEntry) row).id().name();
    }

    /** The selected version; for a group its newest. */
    private HistoryEntry selected() {
        Object o = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
        return o instanceof HistoryGroup g ? g.newest() : o instanceof HistoryEntry e ? e : null;
    }

    private void openCopy() {
        HistoryEntry e = selected();
        if (e == null || model == null) {
            return;
        }
        try (SharedModel sm = SharedModel.open(model.getFile().toPath())) {
            Path dir = Files.createTempDirectory("cooperativeArchi");
            Path file = dir.resolve(model.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + " (version " + e.shortId() + ").archimate");
            Files.write(file, sm.modelAt(e.id()));
            ModelSupport.open(file);
            ModelSupport.notify("Old version opened", "This is a read-only copy for comparison: changes to it are not shared.");
        } catch (IOException ex) {
            MessageDialog.openError(getSite().getShell(), "History", ex.getMessage());
        }
    }

    private void restore() {
        HistoryEntry e = selected();
        if (e == null || model == null) {
            return;
        }
        if (ModelSupport.isDirty(model)) {
            MessageDialog.openInformation(getSite().getShell(), "Restore", "Save or discard your current changes first.");
            return;
        }
        if (!MessageDialog.openConfirm(getSite().getShell(), "Restore version",
                "Make the model look like version " + e.shortId() + " (" + WHEN.format(e.time()) + ")?\n\n"
                        + "This is uploaded as a new change on the next Push; history is kept, so it can be undone the same way.")) {
            return;
        }
        try (SharedModel sm = SharedModel.open(model.getFile().toPath())) {
            sm.restoreVersion(e.id(), e.time());
            IArchimateModel reloaded = ModelSupport.reload(model);
            if (reloaded != null) {
                model = reloaded;
            }
        } catch (IOException ex) {
            MessageDialog.openError(getSite().getShell(), "Restore", ex.getMessage());
            return;
        }
        SyncRunner.Outcome o = SyncRunner.sync(model, SyncRunner.Mode.INTERACTIVE, null);
        if (o.model() != null) {
            model = o.model();
        }
        refresh();
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        SyncRunner.LISTENERS.remove(refresher);
        getSite().getPage().removePostSelectionListener(selectionListener);
        super.dispose();
    }
}
