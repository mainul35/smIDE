package com.smide.debug;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.editor.CodeEditor;
import com.smide.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.util.List;

/**
 * The Debug tool window: frames on the left, variables on the right, and the buttons
 * that move the program on.
 *
 * <p>It knows nothing about JDWP - a plugin hands it a {@link DebugSession} and this
 * draws whatever that reports, so a debugger for another language needs no change here.
 */
public final class DebugToolWindow implements ToolWindowFactory {

    public static final String ID = "debug";

    private final Ide ide;
    private final ListView<DebugSession.StackFrameInfo> frames = new ListView<>();
    private final TreeView<DebugSession.VariableInfo> variables = new TreeView<>();
    private final TreeItem<DebugSession.VariableInfo> variablesRoot = new TreeItem<>();
    private final Label status = new Label("No debug session.");
    private final StackPane pane = new StackPane();
    private final HBox controls = new HBox(2);
    private BorderPane root;
    private ToolWindowContext context;
    private DebugSession session;
    private CodeEditor markedEditor;

    public DebugToolWindow(Ide ide) {
        this.ide = ide;
        frames.setCellFactory(v -> new FrameCell());
        frames.getStyleClass().add("debug-frames");
        frames.getSelectionModel().selectedItemProperty().addListener((o, a, frame) -> {
            showVariables(frame);
            if (frame != null && frame.file() != null && Files.isRegularFile(frame.file())) {
                ide.editors().open(frame.file(), frame.line(), 0);
                mark(frame);
            }
        });
        variables.setRoot(variablesRoot);
        variables.setShowRoot(false);
        variables.getStyleClass().add("debug-variables");
        variables.setCellFactory(v -> new VariableCell());
        status.getStyleClass().add("empty-hint");
    }

    /** Shows a session and follows it until it ends. */
    public void setSession(DebugSession session) {
        this.session = session;
        session.addListener(s -> refresh());
        if (context != null) {
            context.show();
        }
        refresh();
    }

    public DebugSession session() {
        return session;
    }

    private void refresh() {
        boolean live = session != null && session.isRunning();
        boolean suspended = live && session.isSuspended();
        controls.setDisable(!live);
        for (Node node : controls.getChildren()) {
            if (node instanceof Button b && b.getUserData() instanceof String action) {
                // Stop works whenever the session is alive; the rest need it stopped somewhere.
                b.setDisable(!live || (!suspended && !"stop".equals(action)));
            }
        }
        if (!live) {
            status.setText(session == null ? "No debug session." : "Debug session ended.");
            frames.getItems().clear();
            variablesRoot.getChildren().clear();
            clearMark();
            if (context != null) {
                context.setTitle("Debug");
            }
            return;
        }
        if (!suspended) {
            status.setText("Running. The program stops at the next breakpoint.");
            frames.getItems().clear();
            variablesRoot.getChildren().clear();
            clearMark();
            if (context != null) {
                context.setTitle("Debug  running");
            }
            return;
        }
        List<DebugSession.StackFrameInfo> stack = session.frames();
        frames.getItems().setAll(stack);
        if (!stack.isEmpty()) {
            frames.getSelectionModel().select(0);
        }
        status.setText("Stopped.");
        if (context != null) {
            context.setTitle("Debug  stopped");
        }
    }

    private void showVariables(DebugSession.StackFrameInfo frame) {
        variablesRoot.getChildren().clear();
        if (session == null || frame == null || !session.isSuspended()) {
            return;
        }
        for (DebugSession.VariableInfo variable : session.variables(frame)) {
            variablesRoot.getChildren().add(node(variable));
        }
    }

    /** A variable node that fetches its children the first time it is opened. */
    private TreeItem<DebugSession.VariableInfo> node(DebugSession.VariableInfo variable) {
        TreeItem<DebugSession.VariableInfo> item = new TreeItem<>(variable) {
            @Override
            public boolean isLeaf() {
                return !variable.expandable();
            }
        };
        if (variable.expandable()) {
            item.getChildren().add(new TreeItem<>());
            item.expandedProperty().addListener((o, was, now) -> {
                if (now && item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null) {
                    item.getChildren().clear();
                    for (DebugSession.VariableInfo child : session.children(variable)) {
                        item.getChildren().add(node(child));
                    }
                }
            });
        }
        return item;
    }

    /** Puts the execution arrow in the editor's gutter. */
    private void mark(DebugSession.StackFrameInfo frame) {
        clearMark();
        ide.editors().find(frame.file()).ifPresent(editor -> {
            if (editor instanceof CodeEditor code) {
                code.setExecutionLine(frame.line());
                markedEditor = code;
            }
        });
    }

    private void clearMark() {
        if (markedEditor != null) {
            markedEditor.setExecutionLine(-1);
            markedEditor = null;
        }
    }

    private Button control(String icon, String tip, String action, Runnable run) {
        Button button = new Button();
        button.setGraphic(new FontIcon(icon));
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.setUserData(action);
        button.setDisable(true);
        button.setOnAction(e -> {
            run.run();
            refresh();
        });
        return button;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Debug";
    }

    @Override
    public String iconLiteral() {
        return "mdi2b-bug";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+5";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        if (root == null) {
            controls.getChildren().addAll(
                    control("fth-play", "Resume (F9)", "resume", () -> session.resume()),
                    control("fth-corner-down-right", "Step over (F8)", "over", () -> session.stepOver()),
                    control("fth-arrow-down", "Step into (F7)", "into", () -> session.stepInto()),
                    control("fth-arrow-up", "Step out (Shift+F8)", "out", () -> session.stepOut()),
                    control("fth-square", "Stop", "stop", () -> session.stop()));
            controls.setAlignment(Pos.CENTER_LEFT);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox bar = new HBox(6, controls, gap, status);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(4, 8, 4, 6));
            SplitPane split = new SplitPane(frames, variables);
            split.setDividerPositions(0.42);
            root = new BorderPane(split);
            root.setTop(bar);
            pane.getChildren().add(root);
        }
        refresh();
        return pane;
    }

    private static final class FrameCell extends ListCell<DebugSession.StackFrameInfo> {
        @Override
        protected void updateItem(DebugSession.StackFrameInfo frame, boolean empty) {
            super.updateItem(frame, empty);
            if (empty || frame == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String where = frame.file() == null ? "" : frame.file().getFileName() + ":" + (frame.line() + 1);
            setText(frame.description() + (where.isEmpty() ? "" : "   " + where));
            setGraphic(Icons.of(frame.index() == 0 ? "fth-chevron-right" : "fth-more-horizontal", 12));
        }
    }

    private static final class VariableCell extends TreeCell<DebugSession.VariableInfo> {
        @Override
        protected void updateItem(DebugSession.VariableInfo variable, boolean empty) {
            super.updateItem(variable, empty);
            if (empty || variable == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(variable.name());
            Label value = new Label(variable.value());
            value.getStyleClass().add("debug-value");
            Label type = new Label(variable.type() == null ? "" : variable.type());
            type.getStyleClass().add("debug-type");
            HBox row = new HBox(8, name, new Label("="), value, type);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
