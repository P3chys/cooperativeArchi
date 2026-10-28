package io.github.p3chys.cooperativearchi.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import org.eclipse.jface.resource.JFaceResources;

import io.github.p3chys.cooperativearchi.merge.Conflict;
import io.github.p3chys.cooperativearchi.merge.Conflict.Side;
import io.github.p3chys.cooperativearchi.merge.ModelMerger.MergeSession;

/**
 * Lists the things both sides changed. Click "Mine" or "Theirs" in a row to pick the version to keep;
 * the kept value is shown in bold.
 */
final class ConflictDialog extends TitleAreaDialog {

    private final MergeSession session;
    private final String theirsLabel;
    private TableViewer viewer;

    ConflictDialog(Shell parent, MergeSession session, String theirsLabel) {
        super(parent);
        this.session = session;
        this.theirsLabel = theirsLabel;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Resolve conflicts");
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1000, 520);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(session.conflicts().size() == 1 ? "1 thing was changed by you and by a colleague"
                : session.conflicts().size() + " things were changed by you and by a colleague");
        setMessage("Click the version to keep in each row (bold = kept). Everything else was merged automatically.");

        Composite area = (Composite) super.createDialogArea(parent);
        Composite box = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(box);
        box.setLayout(new GridLayout(1, false));

        Composite tableBox = new Composite(box, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tableBox);
        TableColumnLayout layout = new TableColumnLayout();
        tableBox.setLayout(layout);
        viewer = new TableViewer(tableBox, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
        Color dim = table.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
        column(layout, "Item", 30, c -> c.item(), null, null, null);
        column(layout, "What", 14, c -> c.what(), null, null, null);
        column(layout, "Mine", 28, c -> c.mine(), Side.MINE, bold, dim);
        column(layout, "Theirs (" + theirsLabel + ")", 28, c -> c.theirs(), Side.THEIRS, bold, dim);

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(session.conflicts());
        table.addListener(SWT.MouseDown, e -> {
            var item = table.getItem(new Point(e.x, e.y));
            if (item == null) {
                return;
            }
            Conflict c = (Conflict) item.getData();
            if (item.getBounds(2).contains(e.x, e.y)) {
                c.choose(Side.MINE);
            } else if (item.getBounds(3).contains(e.x, e.y)) {
                c.choose(Side.THEIRS);
            }
            viewer.refresh(c);
        });

        Composite buttons = new Composite(box, SWT.NONE);
        buttons.setLayout(new GridLayout(3, false));
        Button allMine = new Button(buttons, SWT.PUSH);
        allMine.setText("Keep all mine");
        allMine.addListener(SWT.Selection, e -> chooseAll(Side.MINE));
        Button allTheirs = new Button(buttons, SWT.PUSH);
        allTheirs.setText("Keep all theirs");
        allTheirs.addListener(SWT.Selection, e -> chooseAll(Side.THEIRS));
        Label hint = new Label(buttons, SWT.WRAP);
        hint.setText("  Cancel leaves everything unchanged.");
        return area;
    }

    private void chooseAll(Side side) {
        session.conflicts().forEach(c -> c.choose(side));
        viewer.refresh();
    }

    private void column(TableColumnLayout layout, String title, int weight, java.util.function.Function<Conflict, String> text,
            Side side, Font bold, Color dim) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        layout.setColumnData(col.getColumn(), new ColumnWeightData(weight, 80));
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                String s = text.apply((Conflict) element);
                if (side != null && ((Conflict) element).choice() == side) {
                    return "✔ " + s;
                }
                return s;
            }

            @Override
            public Font getFont(Object element) {
                return side != null && ((Conflict) element).choice() == side ? bold : null;
            }

            @Override
            public Color getForeground(Object element) {
                return side != null && ((Conflict) element).choice() != side ? dim : null;
            }

            @Override
            public String getToolTipText(Object element) {
                return text.apply((Conflict) element);
            }
        });
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Apply and sync", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
