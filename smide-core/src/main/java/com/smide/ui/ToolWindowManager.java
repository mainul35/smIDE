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
 * <p>Panels are added to and removed from split panes on show and hide, with the
 * divider positions remembered, so a hidden panel costs no space at all.
 */
public final class ToolWindowManager implements ToolWindows {

    private static final class Entry {
        final ToolWindowFactory factory;
        final ToggleButton button;
        Node content;
        String title;

        Entry(ToolWindowFactory factory, ToggleButton button) {
            this.factory = factory;
            this.button = button;
            this.title = factory.title();
        }
    }

    private final ExtensionRegistry registry;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<ToolWindowAnchor, String> visible = new HashMap<>();
    private final Map<ToolWindowAnchor, Double> dividers = new HashMap<>();

    private final VBox leftStripeTop = new VBox();
    private final VBox leftStripeBottom = new VBox();
    private final VBox leftStripe = new VBox();
    private final VBox rightStripe = new VBox();
    private final SplitPane horizontal = new SplitPane();
    private final SplitPane vertical = new SplitPane();
    private final BorderPane leftPanel = panel();
    private final BorderPane rightPanel = panel();
    private final BorderPane bottomPanel = panel();
    private final Node center;
    private final HBox root = new HBox();

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
        dividers.put(ToolWindowAnchor.BOTTOM, 0.68);

        for (ToolWindowFactory f : registry.toolWindows()) {
            register(f);
        }
        registry.onToolWindowAdded(this::register);
    }

    public Node node() {
        return root;
    }

    private static BorderPane panel() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("tool-window");
        return pane;
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
        ToolWindowAnchor anchor = entry.factory.anchor();
        String current = visible.get(anchor);
        if (current != null && !current.equals(id)) {
            hide(current);
        }
        if (entry.content == null) {
            entry.content = entry.factory.create(new ToolWindowFactory.ToolWindowContext() {
                @Override
                public void setTitle(String title) {
                    entry.title = title;
                    if (id.equals(visible.get(anchor))) {
                        setHeader(panelFor(anchor), entry);
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
            entry.content.getStyleClass().add("tool-window-content");
        }
        BorderPane panel = panelFor(anchor);
        setHeader(panel, entry);
        panel.setCenter(entry.content);
        if (!isPanelShown(anchor)) {
            attach(anchor, panel);
        }
        visible.put(anchor, id);
        entry.button.setSelected(true);
        entry.content.requestFocus();
    }

    private void setHeader(BorderPane panel, Entry entry) {
        Label title = new Label(entry.title.toUpperCase());
        title.getStyleClass().add("tool-window-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(title, gap, Icons.button("fth-minus", "Hide", () -> hide(entry.factory.id())));
        header.getStyleClass().add("tool-window-header");
        panel.setTop(header);
    }

    private BorderPane panelFor(ToolWindowAnchor anchor) {
        return switch (anchor) {
            case LEFT -> leftPanel;
            case RIGHT -> rightPanel;
            case BOTTOM -> bottomPanel;
        };
    }

    private boolean isPanelShown(ToolWindowAnchor anchor) {
        return switch (anchor) {
            case LEFT, RIGHT -> horizontal.getItems().contains(panelFor(anchor));
            case BOTTOM -> vertical.getItems().contains(bottomPanel);
        };
    }

    private void attach(ToolWindowAnchor anchor, BorderPane panel) {
        panel.setMinWidth(160);
        panel.setMinHeight(80);
        SplitPane.setResizableWithParent(panel, false);
        switch (anchor) {
            case LEFT -> {
                horizontal.getItems().add(0, panel);
                setDivider(horizontal, 0, dividers.get(anchor));
            }
            case RIGHT -> horizontal.getItems().add(panel);
            case BOTTOM -> {
                vertical.getItems().add(panel);
                setDivider(vertical, 0, dividers.get(anchor));
            }
        }
        if (anchor == ToolWindowAnchor.RIGHT) {
            setDivider(horizontal, horizontal.getItems().size() - 2, dividers.get(anchor));
        }
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

    private void detach(ToolWindowAnchor anchor, BorderPane panel) {
        switch (anchor) {
            case LEFT -> {
                if (horizontal.getItems().contains(panel)) {
                    dividers.put(anchor, horizontal.getDividerPositions()[0]);
                    horizontal.getItems().remove(panel);
                }
            }
            case RIGHT -> {
                if (horizontal.getItems().contains(panel)) {
                    double[] positions = horizontal.getDividerPositions();
                    dividers.put(anchor, positions[positions.length - 1]);
                    horizontal.getItems().remove(panel);
                }
            }
            case BOTTOM -> {
                if (vertical.getItems().contains(panel)) {
                    dividers.put(anchor, vertical.getDividerPositions()[0]);
                    vertical.getItems().remove(panel);
                }
            }
        }
    }

    @Override
    public void hide(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return;
        }
        ToolWindowAnchor anchor = entry.factory.anchor();
        if (id.equals(visible.get(anchor))) {
            detach(anchor, panelFor(anchor));
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
            if (isPanelShown(anchor)) {
                detachSnapshot(anchor);
            }
            out.put(anchor.name(), dividers.get(anchor));
        }
        return out;
    }

    private void detachSnapshot(ToolWindowAnchor anchor) {
        switch (anchor) {
            case LEFT -> dividers.put(anchor, horizontal.getDividerPositions()[0]);
            case RIGHT -> {
                double[] positions = horizontal.getDividerPositions();
                dividers.put(anchor, positions[positions.length - 1]);
            }
            case BOTTOM -> dividers.put(anchor, vertical.getDividerPositions()[0]);
        }
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
