package io.github.p3chys.cooperativearchi.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
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
import io.github.p3chys.cooperativearchi.git.SharedModel;

/** Versions of the selected shared model: who changed what and when; open or restore old versions. */
public class HistoryView extends ViewPart {

    public static final String ID = "io.github.p3chys.cooperativearchi.historyView";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    private Label header;
    private TableViewer viewer;
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
        TableColumnLayout layout = new TableColumnLayout();
        tableBox.setLayout(layout);
        viewer = new TableViewer(tableBox, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        ColumnViewerToolTipSupport.enableFor(viewer);

        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
        Color grey = parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
        column(layout, "When", 18, e -> WHEN.format(e.time()), bold, grey);
        column(layout, "Who", 14, HistoryEntry::author, bold, grey);
        column(layout, "What", 58, e -> (e.current() ? "▶ " : "") + e.message().lines().findFirst().orElse("")
                + (e.uploaded() ? "" : "   [not uploaded yet]"), bold, grey);
        column(layout, "Version", 10, HistoryEntry::shortId, bold, grey);

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
        viewer.getTable().setMenu(menu.createContextMenu(viewer.getTable()));
        viewer.addDoubleClickListener(e -> openCopy());
        getViewSite().getActionBars().getToolBarManager().add(refresh);

        getSite().getPage().addPostSelectionListener(selectionListener);
        SyncRunner.LISTENERS.add(refresher);
        model = ModelSupport.activeModel(getSite().getPage().getActivePart(), getSite().getPage().getSelection());
        refresh();
    }

    private void column(TableColumnLayout layout, String title, int weight, java.util.function.Function<HistoryEntry, String> text,
            Font bold, Color grey) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        layout.setColumnData(col.getColumn(), new ColumnWeightData(weight, 60));
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return text.apply((HistoryEntry) element);
            }

            @Override
            public String getToolTipText(Object element) {
                HistoryEntry e = (HistoryEntry) element;
                return e.message() + "\n\n" + e.author() + " <" + e.email() + ">, " + WHEN.format(e.time()) + "\nVersion "
                        + e.id().name();
            }

            @Override
            public Font getFont(Object element) {
                return ((HistoryEntry) element).current() ? bold : null;
            }

            @Override
            public Color getForeground(Object element) {
                return ((HistoryEntry) element).merge() ? grey : null;
            }
        });
    }

    void refresh() {
        if (viewer == null || viewer.getTable().isDisposed()) {
            return;
        }
        if (model == null || model.getFile() == null || !ModelSupport.isShared(model)) {
            header.setText("Select a shared model to see its history.");
            viewer.setInput(List.of());
            return;
        }
        try (SharedModel sm = SharedModel.open(model.getFile().toPath())) {
            header.setText("'" + model.getName() + "'  —  branch " + sm.branch() + "  —  ▶ = the version you have");
            viewer.setInput(sm.history(300));
        } catch (IOException e) {
            header.setText("Cannot read history: " + e.getMessage());
        }
        header.getParent().layout();
    }

    private HistoryEntry selected() {
        Object o = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
        return o instanceof HistoryEntry e ? e : null;
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
                        + "This is shared as a new change on Sync; history is kept, so it can be undone the same way.")) {
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
