package com.smide.ui;

import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.ui.ToolWindows;
import com.smide.core.ExtensionRegistry;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool windows around the editor, the way an IDE carries them: a stripe of buttons down
 * each edge, and one panel per edge showing whichever tool window on that edge was
 * chosen last. Bottom tool windows put their buttons on the lower half of the left stripe.
 *
 * <p>Each tool window owns its panel, and showing one puts that panel into the split
 * while taking the previous one out. An earlier version kept one panel per edge and
 * swapped the node inside it, which looked equivalent and was not: the terminal is a
 * Swing component inside a {@code SwingNode}, and swapping around it left its surface
 * painting over the panel - the Debug window was there, underneath a terminal that had
 * already been replaced, header and all. Removing the whole panel from the split leaves
 * the layout no stale content to keep.
 */
public final class ToolWindowManager implements ToolWindows {

    private static final class Entry {
        final ToolWindowFactory factory;
        final ToggleButton button;
        final BorderPane panel = new BorderPane();
        Node content;
        String title;

        Entry(ToolWindowFactory factory, ToggleButton button) {
            this.factory = factory;
            this.button = button;
            this.title = factory.title();
            panel.getStyleClass().add("tool-window");
        }
    }

    private final ExtensionRegistry registry;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<ToolWindowAnchor, String> visible = new EnumMap<>(ToolWindowAnchor.class);
    private final Map<ToolWindowAnchor, Double> dividers = new EnumMap<>(ToolWindowAnchor.class);

    private final VBox leftStripeTop = new VBox();
    private final VBox leftStripeBottom = new VBox();
    private final VBox leftStripe = new VBox();
    private final VBox rightStripe = new VBox();
    private final SplitPane horizontal = new SplitPane();
    private final SplitPane vertical = new SplitPane();
    private final Node center;
    private final HBox root = new HBox();
    /** The tool window currently being shown, so a nested request can be ignored. */
    private String showing;

    public ToolWindowManager(ExtensionRegistry registry, Node center) {
        this.registry = registry;
        this.center = center;

        leftStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-left");
        rightStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-right");
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        leftStripeTop.setSpacing(2);
        leftStripeBottom.setSpacing(2);
        leftStripeTop.setAlignment(Pos.TOP_CENTER);
        leftStripeBottom.setAlignment(Pos.BOTTOM_CENTER);
        leftStripe.getChildren().addAll(leftStripeTop, spacer, leftStripeBottom);
        rightStripe.setSpacing(2);

        vertical.setOrientation(Orientation.VERTICAL);
        vertical.getItems().add(center);
        horizontal.setOrientation(Orientation.HORIZONTAL);
        horizontal.getItems().add(vertical);
        HBox.setHgrow(horizontal, Priority.ALWAYS);
        root.getChildren().addAll(leftStripe, horizontal, rightStripe);

        dividers.put(ToolWindowAnchor.LEFT, 0.22);
        dividers.put(ToolWindowAnchor.RIGHT, 0.75);
        dividers.put(ToolWindowAnchor.BOTTOM, 0.62);

        for (ToolWindowFactory f : registry.toolWindows()) {
            register(f);
        }
        registry.onToolWindowAdded(this::register);
    }

    public Node node() {
        return root;
    }

    private void register(ToolWindowFactory factory) {
        if (entries.containsKey(factory.id())) {
            return;
        }
        ToggleButton button = new ToggleButton();
        button.getStyleClass().add("stripe-button");
        Node icon = Icons.of(factory.iconLiteral(), 16);
        if (icon != null) {
            button.setGraphic(icon);
        } else {
            button.setText(factory.title().substring(0, 1));
        }
        String tip = factory.shortcut() == null ? factory.title() : factory.title() + "  (" + factory.shortcut() + ")";
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> toggle(factory.id()));
        Entry entry = new Entry(factory, button);
        entries.put(factory.id(), entry);

        VBox stripe = switch (factory.anchor()) {
            case LEFT -> leftStripeTop;
            case BOTTOM -> leftStripeBottom;
            case RIGHT -> rightStripe;
        };
        // Keep stripe order by factory order.
        List<Node> buttons = new ArrayList<>(stripe.getChildren());
        buttons.add(button);
        buttons.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
        stripe.getChildren().setAll(buttons);
        button.setUserData(entry);
    }

    private int orderOf(Node button) {
        Object data = button.getUserData();
        return data instanceof Entry e ? e.factory.order() : Integer.MAX_VALUE;
    }

    @Override
    public void show(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return;
        }
        /* A tool window may ask to be shown while it is still being built - the terminal
           opens its first session in create(), and the tab that appears asks the window
           to come forward. That request arrives inside a list-change notification, where
           JavaFX refuses any further change to the scene graph, and the call is redundant
           anyway: the outer show() is about to do exactly this. */
        if (id.equals(showing)) {
            return;
        }
        ToolWindowAnchor anchor = entry.factory.anchor();
        String current = visible.get(anchor);
        if (id.equals(current)) {
            entry.button.setSelected(true);
            focus(entry);
            return;
        }
        showing = id;
        try {
            if (current != null) {
                hide(current);
            }
            if (entry.content == null) {
                entry.content = create(entry, anchor);
            }
            entry.panel.setTop(header(entry));
            entry.panel.setCenter(entry.content);
            entry.panel.setMinWidth(160);
            entry.panel.setMinHeight(80);
            attach(anchor, entry.panel);
            visible.put(anchor, id);
            entry.button.setSelected(true);
        } finally {
            showing = null;
        }
        focus(entry);
    }

    private Node create(Entry entry, ToolWindowAnchor anchor) {
        String id = entry.factory.id();
        Node content;
        try {
            content = build(entry, anchor);
        } catch (RuntimeException e) {
            System.err.println("smIDE: tool window " + id + " failed to open: " + e);
            e.printStackTrace();
            Label failed = new Label(entry.factory.title() + " could not open: " + e);
            failed.getStyleClass().add("empty-hint");
            failed.setWrapText(true);
            content = failed;
        }
        content.getStyleClass().add("tool-window-content");
        return content;
    }

    private Node build(Entry entry, ToolWindowAnchor anchor) {
        String id = entry.factory.id();
        return entry.factory.create(new ToolWindowFactory.ToolWindowContext() {
            @Override
            public void setTitle(String title) {
                entry.title = title;
                if (id.equals(visible.get(anchor))) {
                    entry.panel.setTop(header(entry));
                }
            }

            @Override
            public void show() {
                ToolWindowManager.this.show(id);
            }

            @Override
            public void hide() {
                ToolWindowManager.this.hide(id);
            }
        });
    }

    private static void focus(Entry entry) {
        if (entry.content != null) {
            entry.content.requestFocus();
        }
    }

    private Node header(Entry entry) {
        Label title = new Label(entry.title.toUpperCase());
        title.getStyleClass().add("tool-window-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(title, gap, Icons.button("fth-minus", "Hide", () -> hide(entry.factory.id())));
        header.getStyleClass().add("tool-window-header");
        return header;
    }

    private SplitPane splitFor(ToolWindowAnchor anchor) {
        return anchor == ToolWindowAnchor.BOTTOM ? vertical : horizontal;
    }

    private void attach(ToolWindowAnchor anchor, BorderPane panel) {
        SplitPane split = splitFor(anchor);
        SplitPane.setResizableWithParent(panel, false);
        int index;
        switch (anchor) {
            case LEFT -> {
                split.getItems().add(0, panel);
                index = 0;
            }
            case RIGHT -> {
                split.getItems().add(panel);
                index = split.getItems().size() - 2;
            }
            default -> {
                split.getItems().add(panel);
                index = 0;
            }
        }
        setDivider(split, index, dividers.get(anchor));
    }

    /**
     * Positions a divider now and again after layout.
     *
     * <p>A fraction set before the split pane has been laid out is applied against the
     * width it has at that moment, which is not the width it ends up with - and a child
     * with a large preferred size (a WebView reports 800x600) then keeps the panel at
     * its minimum. Setting it a second time, once a pulse has run, makes the fraction
     * mean what it says.
     */
    private static void setDivider(SplitPane pane, int index, double position) {
        pane.setDividerPosition(index, position);
        javafx.application.Platform.runLater(() -> {
            if (index < pane.getDividers().size()) {
                pane.setDividerPosition(index, position);
            }
        });
    }

    private void remember(ToolWindowAnchor anchor) {
        double[] positions = splitFor(anchor).getDividerPositions();
        if (positions.length == 0) {
            return;
        }
        dividers.put(anchor, anchor == ToolWindowAnchor.RIGHT ? positions[positions.length - 1] : positions[0]);
    }

    @Override
    public void hide(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return;
        }
        ToolWindowAnchor anchor = entry.factory.anchor();
        if (id.equals(visible.get(anchor))) {
            remember(anchor);
            splitFor(anchor).getItems().remove(entry.panel);
            // The content leaves the scene with its panel, so a Swing surface inside it
            // stops being drawn instead of hanging over whatever comes next.
            entry.panel.setCenter(null);
            visible.remove(anchor);
        }
        entry.button.setSelected(false);
    }

    @Override
    public void toggle(String id) {
        if (isVisible(id)) {
            hide(id);
        } else {
            show(id);
        }
    }

    @Override
    public boolean isVisible(String id) {
        Entry entry = entries.get(id);
        return entry != null && id.equals(visible.get(entry.factory.anchor()));
    }

    @Override
    public List<ToolWindowFactory> all() {
        return registry.toolWindows();
    }

    @Override
    public Optional<ToolWindowFactory> byId(String id) {
        Entry entry = entries.get(id);
        return Optional.ofNullable(entry == null ? null : entry.factory);
    }

    public void hideAll() {
        for (String id : List.copyOf(visible.values())) {
            hide(id);
        }
    }

    /** Which tool window is showing on each edge, for the session file. */
    public Map<String, String> visibleByAnchor() {
        Map<String, String> out = new HashMap<>();
        visible.forEach((anchor, id) -> out.put(anchor.name(), id));
        return out;
    }

    public Map<String, Double> dividerPositions() {
        Map<String, Double> out = new HashMap<>();
        for (ToolWindowAnchor anchor : ToolWindowAnchor.values()) {
            if (visible.containsKey(anchor)) {
                remember(anchor);
            }
            out.put(anchor.name(), dividers.get(anchor));
        }
        return out;
    }

    public void restore(Map<String, String> visibleByAnchor, Map<String, Double> dividerPositions) {
        if (dividerPositions != null) {
            for (ToolWindowAnchor anchor : ToolWindowAnchor.values()) {
                Double d = dividerPositions.get(anchor.name());
                if (d != null && d > 0.05 && d < 0.95) {
                    dividers.put(anchor, d);
                }
            }
        }
        if (visibleByAnchor != null) {
            for (String id : visibleByAnchor.values()) {
                if (entries.containsKey(id)) {
                    show(id);
                }
            }
        }
    }
}
