package com.smide.execution;

import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

/** The Run tool window: one tab per process, newest selected. */
public final class RunToolWindow implements ToolWindowFactory {

    public static final String ID = "run";

    private final ExecutionService execution;
    private final TabPane tabs = new TabPane();
    private final StackPane root = new StackPane();
    private final Label empty = new Label("Nothing has been run yet. Choose a run configuration and press Shift+F10.");
    private ToolWindowContext context;

    public RunToolWindow(ExecutionService execution) {
        this.execution = execution;
        tabs.getStyleClass().add("document-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        empty.getStyleClass().add("empty-hint");
        root.getChildren().addAll(empty, tabs);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) c -> {
            boolean any = !tabs.getTabs().isEmpty();
            empty.setVisible(!any);
            tabs.setVisible(any);
        });
        empty.setVisible(true);
        tabs.setVisible(false);
        execution.addStartListener(this::addConsole);
    }

    private void addConsole(ProcessConsole console) {
        Tab tab = new Tab(console.title());
        tab.setGraphic(Icons.of("fth-play", 12));
        ConsoleView view = console.view();
        view.toolbar().getChildren().add(0, Icons.button("fth-rotate-cw", "Rerun", () -> {
            if (!console.isRunning()) {
                tabs.getTabs().remove(tab);
                execution.run(console.spec());
            }
        }));
        view.toolbar().getChildren().add(1, Icons.button("fth-square", "Stop (Ctrl+F2)", console::stop));
        view.toolbar().getChildren().add(2, Icons.button("fth-trash-2", "Clear", view::clear));
        tab.setContent(view);
        tab.setOnCloseRequest(e -> {
            if (console.isRunning()) {
                console.stop();
            }
        });
        console.exitCode().thenRun(() -> Platform.runLater(() -> tab.setGraphic(Icons.of("fth-check", 12))));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        if (context != null) {
            context.show();
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Run";
    }

    @Override
    public String iconLiteral() {
        return "fth-play";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+4";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        return root;
    }
}
