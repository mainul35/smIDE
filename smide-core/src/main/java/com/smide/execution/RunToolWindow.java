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

import java.util.List;

/**
 * The Run tool window: one tab per process, newest selected.
 *
 * <p>Not one tab per process for ever, though. A console holds everything its process printed -
 * megabytes of it, in a text area that costs several times what the characters do - and nothing
 * used to let go of one until a developer closed that tab by hand. A session that runs the same
 * build thirty times ended up with thirty consoles of Maven output on the heap, which is how a
 * two-hour session ran out of memory and took the Java runtime down with it.
 *
 * <p>So a finished tab for the same command is used again rather than added to, and beyond
 * {@link #MOST_TABS} the oldest finished one goes. Anything still running is left alone.
 */
public final class RunToolWindow implements ToolWindowFactory {

    public static final String ID = "run";

    /** How many consoles are worth keeping. Past this, the oldest finished one is let go. */
    private static final int MOST_TABS = 12;

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
        tab.setUserData(console);
        tab.setOnCloseRequest(e -> {
            if (console.isRunning()) {
                console.stop();
            }
        });
        console.exitCode().thenRun(() -> Platform.runLater(() -> tab.setGraphic(Icons.of("fth-check", 12))));
        replaceFinished(console.title());
        makeRoom();
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        if (context != null) {
            context.show();
        }
    }

    /**
     * Drops the finished tab this one replaces.
     *
     * <p>Running the same thing again is the same job, not a new one: the developer wants the
     * output of this run, and the one before it was theirs to keep only until they asked again.
     * A tab whose process is still going is another matter and is left where it is.
     */
    private void replaceFinished(String title) {
        for (Tab open : List.copyOf(tabs.getTabs())) {
            if (title.equals(open.getText()) && finished(open)) {
                drop(open);
            }
        }
    }

    /** Lets the oldest finished console go when there are more than anybody reads. */
    private void makeRoom() {
        while (tabs.getTabs().size() >= MOST_TABS) {
            Tab oldest = tabs.getTabs().stream().filter(RunToolWindow::finished).findFirst().orElse(null);
            if (oldest == null) {
                // All of them are still running: nothing to reclaim, and nothing worth killing.
                return;
            }
            drop(oldest);
        }
    }

    private static boolean finished(Tab tab) {
        return tab.getUserData() instanceof ProcessConsole console && !console.isRunning();
    }

    /**
     * Closes a tab and lets go of what it was holding.
     *
     * <p>Removing the tab is not enough on its own: the console's own copy of the output outlives
     * it, and the text area holds paragraph objects that the toolkit will keep as long as anything
     * points at them. Both are emptied here, where it is certain nobody is reading them.
     */
    private void drop(Tab tab) {
        if (tab.getUserData() instanceof ProcessConsole console) {
            console.forget();
        }
        if (tab.getContent() instanceof ConsoleView view) {
            view.clear();
        }
        tab.setContent(null);
        tab.setUserData(null);
        tabs.getTabs().remove(tab);
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
