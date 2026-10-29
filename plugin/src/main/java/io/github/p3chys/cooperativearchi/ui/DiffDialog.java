package io.github.p3chys.cooperativearchi.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import io.github.p3chys.cooperativearchi.git.ChangeReviewer.Direction;
import io.github.p3chys.cooperativearchi.merge.ModelDiff;
import io.github.p3chys.cooperativearchi.merge.ModelDiff.Change;
import io.github.p3chys.cooperativearchi.merge.ModelDiff.Entry;
import io.github.p3chys.cooperativearchi.merge.ModelDiff.Kind;

/**
 * Shows what a pull changes in the local model, or what a push changes on GitHub, before it happens: every added,
 * deleted and changed object with each changed value (before / after), grouped by elements, relationships, views...
 * OK applies / uploads, Cancel changes nothing.
 */
final class DiffDialog extends TitleAreaDialog {

    /** Above this many rows only the groups and objects are expanded, not every detail. */
    private static final int EXPAND_ALL_LIMIT = 400;

    private record Group(String heading, List<Entry> entries) {
    }

    private final ModelDiff diff;
    private final Direction direction;
    private final String modelName;

    DiffDialog(Shell parent, ModelDiff diff, Direction direction, String modelName) {
        super(parent);
        this.diff = diff;
        this.direction = direction;
        this.modelName = modelName;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(direction == Direction.PULL ? "Pull: review changes" : "Push: review changes");
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1000, 620);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        if (direction == Direction.PULL) {
            setTitle("Colleagues' changes to your model '" + modelName + "'");
            setMessage("Your model will change like this. Cancel leaves it as it is.");
        } else {
            setTitle("Your changes to the model on GitHub");
            setMessage("The model on GitHub will change like this. Cancel uploads nothing.");
        }
        Composite area = (Composite) super.createDialogArea(parent);
        Composite box = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(box);
        box.setLayout(new GridLayout(1, false));

        Composite treeBox = new Composite(box, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(treeBox);
        TreeColumnLayout layout = new TreeColumnLayout();
        treeBox.setLayout(layout);
        TreeViewer viewer = new TreeViewer(treeBox, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        ColumnViewerToolTipSupport.enableFor(viewer);

        boolean pull = direction == Direction.PULL;
        column(viewer, layout, "What changed", 44, 0);
        column(viewer, layout, pull ? "Your model now" : "On GitHub now", 28, 1);
        column(viewer, layout, pull ? "After the pull" : "After the push", 28, 2);
        viewer.setContentProvider(new ContentProvider());
        List<Group> groups = groups();
        viewer.setInput(groups);
        int rows = diff.entries().stream().mapToInt(e -> 1 + e.changes().size()).sum();
        viewer.expandToLevel(rows <= EXPAND_ALL_LIMIT ? TreeViewer.ALL_LEVELS : 1);

        Composite bottom = new Composite(box, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bottom);
        bottom.setLayout(new GridLayout(2, false));
        Label summary = new Label(bottom, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(summary);
        summary.setText(summary());
        Button copy = new Button(bottom, SWT.PUSH);
        copy.setText("Copy as text");
        copy.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(getShell().getDisplay());
            try {
                cb.setContents(new Object[] { diff.toText() }, new Transfer[] { TextTransfer.getInstance() });
            } finally {
                cb.dispose();
            }
        });
        return area;
    }

    private List<Group> groups() {
        Map<String, List<Entry>> byHeading = new LinkedHashMap<>();
        for (Entry e : diff.entries()) {
            byHeading.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
        }
        List<Group> list = new ArrayList<>();
        byHeading.forEach((heading, entries) -> list.add(new Group(heading, entries)));
        return list;
    }

    private String summary() {
        long added = diff.entries().stream().filter(e -> e.kind() == Kind.ADDED).count();
        long changed = diff.entries().stream().filter(e -> e.kind() == Kind.CHANGED).count();
        long deleted = diff.entries().stream().filter(e -> e.kind() == Kind.DELETED).count();
        String text = diff.entries().isEmpty() ? "No changes to elements, relationships, views or settings."
                : added + " added, " + changed + " changed, " + deleted + " deleted.";
        return diff.layoutNote() == null ? text : text + " " + diff.layoutNote() + ".";
    }

    private static void column(TreeViewer viewer, TreeColumnLayout layout, String title, int weight, int index) {
        TreeViewerColumn col = new TreeViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        layout.setColumnData(col.getColumn(), new ColumnWeightData(weight, 120));
        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
        Color added = viewer.getControl().getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
        Color deleted = viewer.getControl().getDisplay().getSystemColor(SWT.COLOR_DARK_RED);
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return cell(element, index);
            }

            @Override
            public String getToolTipText(Object element) {
                String text = element instanceof Change c ? (index == 1 ? c.before() : index == 2 ? c.after() : c.what())
                        : cell(element, index);
                return text == null || text.isEmpty() ? null : text;
            }

            @Override
            public Font getFont(Object element) {
                return element instanceof Group ? bold : null;
            }

            @Override
            public Color getForeground(Object element) {
                Kind kind = element instanceof Entry e ? e.kind() : element instanceof Change c ? c.kind() : null;
                return kind == Kind.ADDED ? added : kind == Kind.DELETED ? deleted : null;
            }
        });
    }

    private static String cell(Object element, int index) {
        if (element instanceof Group g) {
            return index == 0 ? g.heading() + " (" + g.entries().size() + ")" : "";
        }
        if (element instanceof Entry e) {
            return switch (index) {
                case 0 -> e.toString();
                case 1 -> e.kind() == Kind.ADDED ? "(does not exist)" : "";
                default -> e.kind() == Kind.DELETED ? "(deleted)" : "";
            };
        }
        Change c = (Change) element;
        boolean listedObject = c.before() == null && c.after() == null;
        return switch (index) {
            case 0 -> listedObject ? (c.kind() == Kind.ADDED ? "+ " : "- ") + c.what() : c.what();
            case 1 -> c.kind() == Kind.CHANGED ? ModelDiff.oneLine(c.before()) : "";
            default -> c.kind() == Kind.DELETED || listedObject ? "" : ModelDiff.oneLine(c.after());
        };
    }

    private static final class ContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            return ((List<?>) input).toArray();
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof Group g) {
                return g.entries().toArray();
            }
            return parent instanceof Entry e ? e.changes().toArray() : new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return getChildren(element).length > 0;
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, direction == Direction.PULL ? "Apply to my model" : "Push to GitHub", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
