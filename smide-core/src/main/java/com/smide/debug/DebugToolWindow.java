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
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
    /** Expressions the reader asked for, shown above the variables and re-read on every stop. */
    private final List<String> watches = new ArrayList<>();
    /** Which rows those are, so a watch can be told from a local at a glance. */
    private final Set<TreeItem<DebugSession.VariableInfo>> watchItems =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** The tree rows and their lazy children, shared with the value popup in the editor. */
    private final VariableTree variableTree = new VariableTree(() -> this.session, watchItems::contains);
    private final TextField expression = new TextField();
    private final Label evaluationError = new Label();
    private final HBox evaluateBar = new HBox(6);
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
            if (frame == null) {
                return;
            }
            if (frame.file() != null && Files.isRegularFile(frame.file())) {
                ide.editors().open(frame.file(), frame.line(), 0);
                mark(frame);
                return;
            }
            /* Said rather than ignored. A frame whose source is not on this machine -
               a JDK class, a dependency, a module nobody has opened - used to answer a
               double click with nothing at all, which is indistinguishable from the list
               being dead. */
            ide.statusBar().message("No source for " + frame.description()
                    + " - it is in a library, or its module is not in an open workspace.");
        });
        variables.setRoot(variablesRoot);
        variables.setShowRoot(false);
        variables.getStyleClass().add("debug-variables");
        variableTree.install(variables);
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

    /** The frame selected in the list - the one the reader is looking at - or null. */
    public DebugSession.StackFrameInfo selectedFrame() {
        return frames.getSelectionModel().getSelectedItem();
    }

    /** The stack as shown, innermost first; empty unless the program is stopped. */
    public List<DebugSession.StackFrameInfo> frames() {
        return List.copyOf(frames.getItems());
    }

    private void refresh() {
        boolean live = session != null && session.isRunning();
        boolean suspended = live && session.isSuspended();
        controls.setDisable(!live);
        // Nothing can be read out of a program that is running; the field says so by greying.
        evaluateBar.setDisable(!suspended);
        // The complaint was about the last place it stopped; the program has moved on.
        showError(null);
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
        // Stopping is the moment the window matters; bring it forward every time.
        if (context != null) {
            context.show();
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
        /* Watched expressions first, and read again for this frame. An answer that
           disappeared on the next step would have to be retyped every line, which is most
           of the reason to watch something in the first place. */
        watchItems.clear();
        for (String watch : watches) {
            TreeItem<DebugSession.VariableInfo> item = node(evaluated(watch, frame));
            watchItems.add(item);
            variablesRoot.getChildren().add(item);
        }
        for (DebugSession.VariableInfo variable : session.variables(frame)) {
            variablesRoot.getChildren().add(node(variable));
        }
    }

    /** A watched expression as a row: its value, or the reason there is not one. */
    private DebugSession.VariableInfo evaluated(String watch, DebugSession.StackFrameInfo frame) {
        DebugSession.Evaluation result = session.evaluate(frame, watch);
        return result.ok() ? result.value()
                : new DebugSession.VariableInfo(watch, "", result.error(), false, null);
    }

    /**
     * Reads the expression in the frame on show, and keeps it if it means anything.
     *
     * <p>A failure stays next to the field rather than becoming a row: a typo is something
     * to correct in place, not something to watch.
     */
    private void evaluate() {
        String text = expression.getText() == null ? "" : expression.getText().strip();
        DebugSession.StackFrameInfo frame = frames.getSelectionModel().getSelectedItem();
        if (text.isEmpty() || session == null || !session.isSuspended() || frame == null) {
            return;
        }
        DebugSession.Evaluation result = session.evaluate(frame, text);
        if (!result.ok()) {
            showError(result.error());
            return;
        }
        showError(null);
        watches.remove(text);
        watches.add(0, text);
        showVariables(frame);
        if (!variablesRoot.getChildren().isEmpty()) {
            variables.getSelectionModel().select(variablesRoot.getChildren().get(0));
            variables.scrollTo(0);
        }
        expression.selectAll();
    }

    private void showError(String message) {
        evaluationError.setText(message == null ? "" : message);
        evaluationError.setVisible(message != null);
        evaluationError.setManaged(message != null);
    }

    /** Drops the watch under the cursor; the locals below it are the session's, not ours. */
    private void removeSelectedWatch() {
        TreeItem<DebugSession.VariableInfo> selected = variables.getSelectionModel().getSelectedItem();
        int index = selected == null ? -1 : variablesRoot.getChildren().indexOf(selected);
        if (index >= 0 && index < watches.size()) {
            watches.remove(index);
            showVariables(frames.getSelectionModel().getSelectedItem());
        }
    }

    /**
     * Brings the window up with the caret in the expression field.
     *
     * @param initial what to start from - the editor's selection, usually - or null
     */
    public void focusEvaluate(String initial) {
        if (context != null) {
            context.show();
        }
        if (initial != null && !initial.isBlank() && !initial.contains("\n")) {
            expression.setText(initial.strip());
        }
        showError(null);
        expression.requestFocus();
        expression.selectAll();
    }

    private TreeItem<DebugSession.VariableInfo> node(DebugSession.VariableInfo variable) {
        return variableTree.node(variable);
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

    /** The row along the bottom: type an expression, press Enter, see it in the tree above. */
    private Node evaluateRow() {
        expression.setPromptText("Evaluate an expression - a name, a field, list.size(), args[0]");
        expression.getStyleClass().add("debug-evaluate");
        expression.setOnAction(e -> evaluate());
        HBox.setHgrow(expression, Priority.ALWAYS);
        Button go = Icons.button("fth-corner-down-left", "Evaluate (Enter)", this::evaluate);
        evaluationError.getStyleClass().add("error-text");
        evaluationError.setWrapText(true);
        showError(null);
        evaluateBar.getChildren().addAll(new Label("Evaluate"), expression, go, evaluationError);
        evaluateBar.setAlignment(Pos.CENTER_LEFT);
        evaluateBar.setPadding(new Insets(4, 8, 6, 6));
        // Delete on a watched row drops it, the way Remove works in the other lists.
        variables.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                removeSelectedWatch();
            }
        });
        return evaluateBar;
    }

    private Button control(String icon, String tip, String action, Runnable run) {
        Button button = new Button();
        button.setGraphic(new FontIcon(icon));
        button.getStyleClass().add("icon-button");
        button.setTooltip(com.smide.api.ui.Tooltips.of(tip));
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
            root.setBottom(evaluateRow());
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
}
