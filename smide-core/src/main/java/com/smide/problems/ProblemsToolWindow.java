package com.smide.problems;

import com.smide.api.Ide;
import com.smide.api.problems.Diagnostic;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.ui.Icons;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Diagnostics from every source, grouped by file. Double-click goes to the line. */
public final class ProblemsToolWindow implements ToolWindowFactory {

    public static final String ID = "problems";

    private final Ide ide;
    private final ProblemsService problems;
    private final TreeView<Object> tree = new TreeView<>();
    private final TreeItem<Object> root = new TreeItem<>();
    private final Label empty = new Label("No problems found.");
    private final StackPane pane = new StackPane();
    private ToolWindowContext context;
    private boolean refreshScheduled;

    public ProblemsToolWindow(Ide ide, ProblemsService problems) {
        this.ide = ide;
        this.problems = problems;
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new ProblemCell());
        /* An error in this list is something to paste somewhere else - into a search, a bug
           report, a question for the assistant - and it was the one thing that could not leave
           the window: no menu on the right button, and Ctrl+C did nothing. */
        tree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        tree.setContextMenu(menu());
        tree.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.C
                    && (e.isControlDown() || e.isMetaDown())) {
                copySelected();
                e.consume();
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue() instanceof Diagnostic d) {
                    ide.editors().open(d.file(), d.startLine(), d.startColumn());
                }
            }
        });
        empty.getStyleClass().add("empty-hint");
        pane.getChildren().addAll(empty, tree);
        problems.addAnyChangeListener(this::scheduleRefresh);
        refresh();
    }

    /**
     * The right button: copy what is selected, copy the lot, or go to it.
     *
     * <p>Built once and kept, with what it offers decided as it opens - a menu whose Copy is
     * greyed out when nothing is selected says more than one that copies nothing.
     */
    private javafx.scene.control.ContextMenu menu() {
        javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("Copy");
        copy.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+C"));
        copy.setOnAction(e -> copySelected());

        javafx.scene.control.MenuItem all = new javafx.scene.control.MenuItem("Copy all problems");
        all.setOnAction(e -> put(everything()));

        javafx.scene.control.MenuItem open = new javafx.scene.control.MenuItem("Go to it");
        open.setOnAction(e -> {
            TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
            if (item != null && item.getValue() instanceof Diagnostic d) {
                ide.editors().open(d.file(), d.startLine(), d.startColumn());
            } else if (item != null && item.getValue() instanceof Path p) {
                ide.editors().open(p);
            }
        });

        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                copy, all, new javafx.scene.control.SeparatorMenuItem(), open);
        menu.setOnShowing(e -> {
            boolean something = !tree.getSelectionModel().getSelectedItems().isEmpty();
            copy.setDisable(!something);
            open.setDisable(!something);
            all.setDisable(root.getChildren().isEmpty());
        });
        return menu;
    }

    /** What is selected, as text, one problem per line. */
    private void copySelected() {
        StringBuilder out = new StringBuilder();
        for (TreeItem<Object> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null) {
                out.append(textOf(item.getValue())).append('\n');
            }
        }
        put(out.toString());
    }

    /** Every problem on screen, under the file each belongs to. */
    private String everything() {
        StringBuilder out = new StringBuilder();
        for (TreeItem<Object> file : root.getChildren()) {
            out.append(textOf(file.getValue())).append('\n');
            for (TreeItem<Object> problem : file.getChildren()) {
                out.append("  ").append(textOf(problem.getValue())).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * One row as a line of text.
     *
     * <p>Written the way a compiler writes it - path, line, column, then what is wrong - because
     * that is the form every other tool reads, and because the first thing anybody does with a
     * copied error is paste it into a search.
     */
    static String textOf(Object value) {
        if (value instanceof Diagnostic d) {
            return d.file() + ":" + (d.startLine() + 1) + ":" + (d.startColumn() + 1)
                    + "  " + d.severity().name().toLowerCase(Locale.ROOT) + ": " + d.message()
                    + (d.source() == null || d.source().isBlank() ? "" : "  [" + d.source() + "]");
        }
        if (value instanceof Path p) {
            return p.toString();
        }
        return String.valueOf(value);
    }

    private void put(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text.strip());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        javafx.application.Platform.runLater(() -> {
            refreshScheduled = false;
            refresh();
        });
    }

    private void refresh() {
        Map<Path, List<Diagnostic>> byFile = new TreeMap<>();
        for (Diagnostic d : problems.all()) {
            byFile.computeIfAbsent(d.file(), f -> new ArrayList<>()).add(d);
        }
        root.getChildren().clear();
        for (Map.Entry<Path, List<Diagnostic>> e : byFile.entrySet()) {
            TreeItem<Object> fileItem = new TreeItem<>(e.getKey());
            List<Diagnostic> sorted = new ArrayList<>(e.getValue());
            sorted.sort((a, b) -> a.startLine() != b.startLine()
                    ? Integer.compare(a.startLine(), b.startLine()) : Integer.compare(a.startColumn(), b.startColumn()));
            for (Diagnostic d : sorted) {
                fileItem.getChildren().add(new TreeItem<>(d));
            }
            fileItem.setExpanded(true);
            root.getChildren().add(fileItem);
        }
        boolean any = !byFile.isEmpty();
        empty.setVisible(!any);
        tree.setVisible(any);
        if (context != null) {
            int errors = problems.errorCount();
            int warnings = problems.warningCount();
            context.setTitle(any ? "Problems  " + errors + " errors, " + warnings + " warnings" : "Problems");
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Problems";
    }

    @Override
    public String iconLiteral() {
        return "fth-alert-circle";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+6";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        refresh();
        return pane;
    }

    private final class ProblemCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("severity-error", "severity-warning", "severity-info");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof Path p) {
                setText(p.getFileName() + "   " + (p.getParent() == null ? "" : p.getParent()));
                setGraphic(Icons.of("fth-file-text"));
            } else if (item instanceof Diagnostic d) {
                setText((d.startLine() + 1) + ":" + (d.startColumn() + 1) + "  " + d.message()
                        + (d.source() == null ? "" : "   [" + d.source() + "]"));
                FontIcon icon = Icons.of(switch (d.severity()) {
                    case ERROR -> "fth-x-circle";
                    case WARNING -> "fth-alert-triangle";
                    default -> "fth-info";
                });
                getStyleClass().add("severity-" + d.severity().name().toLowerCase(Locale.ROOT));
                setGraphic(icon);
            }
        }
    }
}
