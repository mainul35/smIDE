package com.smide.ui;

import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.execution.RunConfiguration;
import com.smide.core.ExtensionRegistry;
import com.smide.core.IdeImpl;
import com.smide.execution.ExecutionService;
import com.smide.workspace.WorkspaceManager;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one window: menu bar and toolbar on top, tool windows around the workspace tabs,
 * status bar underneath, notifications floating over the editor area.
 */
public final class MainWindow {

    private static final double SCREEN_FRACTION = 0.85;
    private static final double MIN_WIDTH = 960;
    private static final double MIN_HEIGHT = 620;

    private final IdeImpl ide;
    private final Stage stage;
    private final ExtensionRegistry registry;
    private final WorkspaceManager workspaces;
    private final ExecutionService execution;
    private final BorderPane root = new BorderPane();
    private final StackPane overlay = new StackPane();
    /** Ctrl+plus and Ctrl+minus, scaling the whole window. Always 100% at startup. */
    private final Zoom zoom = new Zoom();
    private final HBox toolbar = new HBox();
    private final MenuButton runChooser = new MenuButton();
    private final StackPane center = new StackPane();
    private final WelcomeView welcome;
    private Scene scene;
    /** Double-Shift: the last completed tap, and what the Shift now down has been used for. */
    private long lastShiftTap;
    private boolean shiftDown;
    private boolean shiftUsedWithAnotherKey;
    private long shiftPressedAt;

    public MainWindow(IdeImpl ide, Stage stage, ExtensionRegistry registry, WorkspaceManager workspaces,
                      ExecutionService execution, ToolWindowManager toolWindows, StatusBarView statusBar,
                      NotificationCenter notifications, WelcomeView welcome) {
        this.ide = ide;
        this.stage = stage;
        this.registry = registry;
        this.workspaces = workspaces;
        this.execution = execution;
        this.welcome = welcome;

        center.getChildren().addAll(welcome, workspaces.tabPane());
        updateWelcome();
        workspaces.addOpenedListener(w -> updateWelcome());
        workspaces.addClosedListener(w -> updateWelcome());

        toolbar.getStyleClass().add("main-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        buildToolbar();
        registry.onActionAdded(a -> buildToolbar());
        execution.addConfigurationsListener(this::refreshRunChooser);
        execution.addStartListener(console -> {
            refreshToolbarEnabled();
            console.exitCode().thenRun(() -> ide.window().runLater(this::refreshToolbarEnabled));
        });
        workspaces.addActiveListener(w -> refreshRunChooser());

        VBox top = new VBox(ide.actionManager().menuBar(), toolbar);
        root.setTop(top);
        root.setCenter(toolWindows.node());
        root.setBottom(statusBar);
        overlay.getChildren().addAll(root, notifications.node());
        StackPane.setAlignment(notifications.node(), Pos.TOP_RIGHT);
        notifications.node().setPickOnBounds(false);
        notifications.node().setMouseTransparent(false);
        notifications.node().setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // Tool windows sit under the notifications overlay; move it below the toolbar.
        notifications.node().translateYProperty().bind(top.heightProperty().add(8));
    }

    /** The centre area, for the tool window manager to wrap. */
    public Node centerNode() {
        return center;
    }

    private void updateWelcome() {
        boolean any = !workspaces.all().isEmpty();
        welcome.setVisible(!any);
        welcome.setManaged(!any);
        workspaces.tabPane().setVisible(any);
        if (!any) {
            welcome.setRecent(workspaces.recent());
        }
    }

    public void show(double x, double y, double width, double height, boolean maximized) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double w = width > 0 ? width : Math.max(MIN_WIDTH, screen.getWidth() * SCREEN_FRACTION);
        double h = height > 0 ? height : Math.max(MIN_HEIGHT, screen.getHeight() * SCREEN_FRACTION);
        w = Math.min(w, screen.getWidth());
        h = Math.min(h, screen.getHeight());
        double sx = x >= 0 ? x : screen.getMinX() + (screen.getWidth() - w) / 2;
        double sy = y >= 0 ? y : screen.getMinY() + (screen.getHeight() - h) / 2;
        boolean onScreen = Screen.getScreensForRectangle(sx, sy, Math.max(100, w), Math.max(100, h)).size() > 0;
        if (!onScreen) {
            sx = screen.getMinX() + (screen.getWidth() - w) / 2;
            sy = screen.getMinY() + (screen.getHeight() - h) / 2;
        }

        boolean restored = width > 0 && height > 0;
        // The scene's root is the holder that scales; everything else is inside it.
        javafx.scene.layout.Pane zoomed = zoom.wrap(overlay);
        scene = restored ? new Scene(zoomed) : new Scene(zoomed, w, h);
        scene.getStylesheets().add(ide.theme().stylesheet());
        ide.theme().style(zoomed);
        ide.theme().style(overlay);
        ide.actionManager().attach(scene);
        installDoubleShift(scene);
        installZoom(scene);

        stage.setTitle("smIDE");
        stage.setScene(scene);
        stage.setMinWidth(Math.min(MIN_WIDTH, screen.getWidth()));
        stage.setMinHeight(Math.min(MIN_HEIGHT, screen.getHeight()));
        stage.setX(sx);
        stage.setY(sy);
        if (restored) {
            // Session sizes are stage sizes, decorations included; a scene of that size
            // would grow the window by the title bar on every launch.
            stage.setWidth(w);
            stage.setHeight(h);
        }
        stage.setOnCloseRequest(e -> {
            e.consume();
            ide.requestExit();
        });
        stage.show();
        if (maximized) {
            stage.setMaximized(true);
        }
        if (Boolean.getBoolean("smide.debugLayout")) {
            javafx.application.Platform.runLater(this::dumpLayout);
        }
    }

    private void dumpLayout() {
        System.err.println("scene " + scene.getWidth() + "x" + scene.getHeight()
                + " overlay " + overlay.getWidth() + " root " + root.getWidth() + " min " + root.minWidth(-1)
                + " top " + root.getTop().getLayoutBounds().getWidth() + " min " + ((Region) root.getTop()).minWidth(-1)
                + " toolbar " + toolbar.getWidth() + " min " + toolbar.minWidth(-1)
                + " center " + ((Region) root.getCenter()).getWidth() + " min " + ((Region) root.getCenter()).minWidth(-1)
                + " bottom " + ((Region) root.getBottom()).getWidth() + " min " + ((Region) root.getBottom()).minWidth(-1));
        for (Node n : toolbar.getChildren()) {
            System.err.println("  toolbar child " + n.getClass().getSimpleName() + " x=" + n.getLayoutX()
                    + " w=" + n.getLayoutBounds().getWidth() + " min=" + (n instanceof Region r ? r.minWidth(-1) : -1)
                    + " pref=" + (n instanceof Region r2 ? r2.prefWidth(-1) : -1));
        }
        dumpMin((Region) root.getCenter(), "  ");
        dumpMin((Region) root.getTop(), "  ");
        dumpMin((Region) root.getBottom(), "  ");
    }

    private void dumpMin(Region region, String indent) {
        if (region.minWidth(-1) < 400) {
            return;
        }
        System.err.println(indent + region.getClass().getSimpleName() + " " + region.getStyleClass()
                + " w=" + region.getWidth() + " min=" + region.minWidth(-1) + " pref=" + region.prefWidth(-1));
        for (Node child : region.getChildrenUnmodifiable()) {
            if (child instanceof Region r) {
                dumpMin(r, indent + "  ");
            }
        }
    }

    public Scene scene() {
        return scene;
    }

    /**
     * Ctrl+plus, Ctrl+minus and Ctrl+0.
     *
     * <p>Three keys for zooming in, because "Ctrl and +" is a different key on almost
     * every keyboard: the plus on the number row is Shift+equals, the numeric keypad has
     * its own, and a layout that has a bare plus reports that. Binding one of them is how
     * a shortcut comes to work on the author's keyboard and nowhere else.
     *
     * <p>A filter rather than an accelerator: the editor has the focus most of the time
     * and consumes the keys it handles, and an accelerator never fires for a key that was
     * consumed before the scene got to it.
     */
    private void installZoom(Scene scene) {
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!e.isShortcutDown() || e.isAltDown()) {
                return;
            }
            switch (e.getCode()) {
                case EQUALS, PLUS, ADD -> {
                    zoom.in();
                    announceZoom();
                    e.consume();
                }
                case MINUS, SUBTRACT -> {
                    zoom.out();
                    announceZoom();
                    e.consume();
                }
                case DIGIT0, NUMPAD0 -> {
                    zoom.reset();
                    announceZoom();
                    e.consume();
                }
                default -> {
                }
            }
        });
    }

    private void announceZoom() {
        ide.statusBar().message("Zoom " + zoom.percent() + "   (Ctrl+0 for 100%)");
    }

    /** The window's zoom, for anything that needs to know how large things are drawn. */
    public Zoom zoom() {
        return zoom;
    }

    /** How long a Shift may be held and still count as a tap rather than a modifier. */
    private static final long SHIFT_TAP = 400;

    /** How long the second tap may take to arrive. */
    private static final long SHIFT_GAP = 350;

    /**
     * Search Everywhere on two taps of Shift, and on nothing else.
     *
     * <p>The rule is stricter than it looks, because every part of it was a way of
     * opening a dialog nobody asked for. A Shift held down to type a capital is a
     * modifier and not a tap, however briefly it is held; a Shift held for a while and
     * then let go is not a tap either, which is what typing an uppercase word does; and
     * any other key in between forgets a first tap, because two capitals a moment apart
     * are two modifiers rather than a gesture. What is left is what somebody means: two
     * deliberate taps of Shift on its own, one after the other.
     */
    private void installDoubleShift(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SHIFT) {
                if (!shiftDown) {
                    // The first press. Holding it repeats the event; that is still one press.
                    shiftDown = true;
                    shiftUsedWithAnotherKey = false;
                    shiftPressedAt = System.currentTimeMillis();
                }
                return;
            }
            shiftUsedWithAnotherKey = true;
            lastShiftTap = 0;
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() != KeyCode.SHIFT) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean tapped = shiftDown && !shiftUsedWithAnotherKey
                    && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                    && now - shiftPressedAt <= SHIFT_TAP;
            shiftDown = false;
            shiftUsedWithAnotherKey = false;
            if (!tapped) {
                lastShiftTap = 0;
                return;
            }
            if (lastShiftTap != 0 && now - lastShiftTap <= SHIFT_GAP) {
                lastShiftTap = 0;
                ide.showSearchEverywhere("");
                e.consume();
            } else {
                lastShiftTap = now;
            }
        });
    }

    // ---------------------------------------------------------------- toolbar

    private void buildToolbar() {
        toolbar.getChildren().clear();
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        toolbar.getChildren().add(gap);

        runChooser.getStyleClass().add("run-config-chooser");
        runChooser.setTooltip(new Tooltip("Run configuration"));
        refreshRunChooser();
        toolbar.getChildren().add(runChooser);

        List<Action> actions = new ArrayList<>();
        for (Action a : registry.actions()) {
            if (a.toolbarGroup() != null) {
                actions.add(a);
            }
        }
        actions.sort((a, b) -> {
            int g = a.toolbarGroup().compareTo(b.toolbarGroup());
            return g != 0 ? g : Integer.compare(a.order(), b.order());
        });
        String lastGroup = null;
        for (Action a : actions) {
            if (lastGroup != null && !lastGroup.equals(a.toolbarGroup())) {
                toolbar.getChildren().add(new Separator(javafx.geometry.Orientation.VERTICAL));
            }
            lastGroup = a.toolbarGroup();
            Button button = new Button();
            FontIcon icon = Icons.of(a.iconLiteral(), 15);
            if (icon != null) {
                button.setGraphic(icon);
            } else {
                button.setText(a.text());
            }
            String shortcut = ide.actions().shortcutOf(a.id()).map(s -> "  (" + s + ")").orElse("");
            button.setTooltip(new Tooltip(a.text() + shortcut));
            button.setFocusTraversable(false);
            button.setOnAction(e -> ide.actions().invoke(a.id()));
            button.setUserData(a);
            toolbar.getChildren().add(button);
        }
        Separator end = new Separator(javafx.geometry.Orientation.VERTICAL);
        toolbar.getChildren().add(end);
        Button theme = new Button();
        theme.setGraphic(Icons.of(ide.theme().isDark() ? "fth-sun" : "fth-moon", 15));
        theme.setTooltip(new Tooltip("Toggle dark theme"));
        theme.setFocusTraversable(false);
        theme.setOnAction(e -> ide.actions().invoke("view.toggleTheme"));
        ide.theme().darkProperty().addListener((o, a, b) -> theme.setGraphic(Icons.of(b ? "fth-sun" : "fth-moon", 15)));
        toolbar.getChildren().add(theme);
        refreshToolbarEnabled();
    }

    /** Re-evaluates enablement, cheap enough to call on every focus or selection change. */
    public void refreshToolbarEnabled() {
        ActionContext ctx = ide.actions().currentContext();
        for (Node n : toolbar.getChildren()) {
            if (n instanceof Button b && b.getUserData() instanceof Action a) {
                b.setDisable(!a.isEnabled(ctx));
            }
        }
    }

    private void refreshRunChooser() {
        runChooser.getItems().clear();
        Optional<RunConfiguration> selected = execution.selectedConfiguration();
        Label label = new Label(selected.map(RunConfiguration::name).orElse("No run configuration"));
        FontIcon icon = Icons.of(selected.map(c -> c.type().iconLiteral()).orElse("fth-play-circle"), 13);
        runChooser.setGraphic(icon == null ? label : new HBox(6, icon, label));
        runChooser.setText("");
        workspaces.active().ifPresent(w -> {
            for (RunConfiguration c : execution.configurations(w)) {
                MenuItem item = new MenuItem(c.name());
                FontIcon i = Icons.of(c.type().iconLiteral(), 13);
                if (i != null) {
                    item.setGraphic(i);
                }
                item.setOnAction(e -> {
                    execution.selectConfiguration(c);
                    refreshToolbarEnabled();
                });
                runChooser.getItems().add(item);
            }
        });
        if (!runChooser.getItems().isEmpty()) {
            runChooser.getItems().add(new SeparatorMenuItem());
        }
        MenuItem edit = new MenuItem("Edit Configurations...");
        edit.setOnAction(e -> ide.actions().invoke("run.editConfigurations"));
        runChooser.getItems().add(edit);
        refreshToolbarEnabled();
    }
}
