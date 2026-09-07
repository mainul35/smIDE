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
