package com.smide.plugins.terminal;

import com.smide.api.Ide;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.workspace.Workspace;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Terminal tool window: a tab per shell session, a "+" for another one.
 *
 * <p>The core hands the content node to a panel when the window is shown and calls
 * {@code requestFocus()} on it; the first showing opens a terminal in the active
 * workspace, later ones just focus the selected terminal.
 */
final class TerminalToolWindow implements ToolWindowFactory {

    static final String ID = "terminal";

    private final Ide ide;
    private final ThemedSettingsProvider settings;
    private final TabPane tabs = new TabPane();
    private final Label empty = new Label("No terminal is open. Press + to start one.");
    private final Map<Tab, TerminalSession> sessions = new HashMap<>();
    /** Next number per tab title, so "smIDE 1", "smIDE 2" ... stay distinct. */
    private final Map<String, Integer> counters = new HashMap<>();
    private final StackPane root;
    private ToolWindowContext context;

    TerminalToolWindow(Ide ide, ThemedSettingsProvider settings) {
        this.ide = ide;
        this.settings = settings;
        root = new StackPane() {
            /** The core focuses the content when the window shows; pass that on to the shell. */
            @Override
            public void requestFocus() {
                super.requestFocus();
                focusSelected();
            }
        };
        tabs.getStyleClass().add("document-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab removed : change.getRemoved()) {
                    TerminalSession session = sessions.remove(removed);
                    if (session != null) {
                        session.dispose();
                    }
                }
            }
            boolean any = !tabs.getTabs().isEmpty();
            empty.setVisible(!any);
            tabs.setVisible(any);
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> focusSelected());
        empty.getStyleClass().add("empty-hint");
        tabs.setVisible(false);

        // Small buttons floating over the right end of the tab strip.
        HBox toolbar = new HBox(2,
                iconButton("fth-plus", "New Terminal", () -> openTerminal(defaultDirectory())),
                iconButton("fth-trash-2", "Clear Terminal", this::clearSelected));
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(1, 4, 0, 0));
        toolbar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toolbar.setPickOnBounds(false);
        StackPane.setAlignment(toolbar, Pos.TOP_RIGHT);

        root.getChildren().addAll(empty, tabs, toolbar);
        root.setMinSize(0, 0);

        // Shown for the first time: open a terminal without waiting to be asked.
        root.parentProperty().addListener((obs, was, now) -> {
            if (now != null) {
                if (tabs.getTabs().isEmpty()) {
                    openTerminal(defaultDirectory());
                } else {
                    focusSelected();
                }
            }
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Terminal";
    }

    @Override
    public String iconLiteral() {
        return "fth-terminal";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+F12";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        return root;
    }

    /** The active workspace's root, or the user's home when nothing is open. */
    Path defaultDirectory() {
        return ide.workspaces().active().map(Workspace::root)
                .orElseGet(() -> Path.of(System.getProperty("user.home")));
    }

    /**
     * Opens a new tab running a shell in {@code directory} and makes it current. The tab is
     * named after the workspace containing the directory (or the directory itself) and
     * numbered. JavaFX thread.
     */
    TerminalSession openTerminal(Path directory) {
        if (directory != null && !Files.isDirectory(directory)) {
            directory = directory.getParent();
        }
        if (directory == null || !Files.isDirectory(directory)) {
            directory = defaultDirectory();
        }
        Path dir = directory;
        String base = ide.workspaces().containing(dir).map(Workspace::name)
                .orElseGet(() -> nameOf(dir));
        int number = counters.merge(base, 1, Integer::sum);
        String title = base + " " + number;

        TerminalSession session;
        try {
            session = new TerminalSession(ide, settings, dir, title);
        } catch (LinkageError | RuntimeException e) {
            /* The emulator is Swing inside a SwingNode, which needs the jdk.unsupported.desktop
               module. A runtime built without it threw here, on the JavaFX thread, before any
               tab existed - so pressing + showed nothing at all, not even an error. */
            counters.merge(base, -1, Integer::sum);
            String message = "The terminal cannot start in this Java runtime: " + e;
            empty.setText(message);
            ide.notifications().error("Terminal", message);
            return null;
        }
        Tab tab = new Tab(title, session.node());
        tab.setGraphic(new FontIcon("fth-terminal"));
        tab.setTooltip(com.smide.api.ui.Tooltips.of(dir.toString()));
        sessions.put(tab, session);
        session.setOnExit(() -> tabs.getTabs().remove(tab));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        session.start();
        if (context != null) {
            context.show();
        }
        return session;
    }

    /** The session in the selected tab, if any. */
    Optional<TerminalSession> selected() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? Optional.empty() : Optional.ofNullable(sessions.get(tab));
    }

    boolean hasSessions() {
        return !sessions.isEmpty();
    }

    void focusSelected() {
        selected().ifPresent(TerminalSession::focus);
    }

    void clearSelected() {
        selected().ifPresent(TerminalSession::clear);
    }

    /** Applies new colours or font to every running terminal. */
    void restyleAll() {
        for (TerminalSession session : sessions.values()) {
            session.restyle();
        }
    }

    /** Kills every shell. Plugin stop. */
    void disposeAll() {
        List<TerminalSession> all = new ArrayList<>(sessions.values());
        sessions.clear();
        tabs.getTabs().clear();
        for (TerminalSession session : all) {
            session.dispose();
        }
    }

    private static String nameOf(Path directory) {
        Path name = directory.getFileName();
        return name == null ? directory.toString() : name.toString();
    }

    private static Button iconButton(String literal, String tooltip, Runnable action) {
        Button button = new Button();
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(14);
        button.setGraphic(icon);
        button.getStyleClass().add("icon-button");
        button.setTooltip(com.smide.api.ui.Tooltips.of(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }
}
