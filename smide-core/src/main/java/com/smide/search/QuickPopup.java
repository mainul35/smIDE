package com.smide.search;

import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.List;
import java.util.function.BiFunction;

/**
 * The popup behind Search Everywhere, Go to File, Find Action and Recent Files: a
 * field, optional tabs, a list. Typing filters, arrows move, Enter chooses, Escape
 * closes. Providers may answer asynchronously by returning quickly and calling
 * {@link #setItems} later; a stale answer is dropped by generation.
 */
public final class QuickPopup {

    public record Item(String primary, String secondary, String iconLiteral, String shortcut, Runnable onChosen) {
    }

    /** Answers a query for a tab; may call {@code publish} later with more results. */
    public interface Provider {
        void query(String tab, String text, int generation, BiFunction<Integer, List<Item>, Boolean> publish);
    }

    private final Popup popup = new Popup();
    private final TextField field = new TextField();
    private final ListView<Item> list = new ListView<>();
    private final HBox tabs = new HBox(4);
    private final Label hint = new Label();
    private final ToggleGroup tabGroup = new ToggleGroup();
    private final Provider provider;
    private final VBox panel;
    private String currentTab;
    private int generation;

    public QuickPopup(String title, List<String> tabNames, String hintText, Provider provider) {
        this.provider = provider;
        panel = new VBox(6);
        panel.getStyleClass().add("popup-panel");
        panel.setPrefWidth(680);

        field.getStyleClass().add("popup-field");
        field.setPromptText(title);
        hint.getStyleClass().add("popup-hint");
        hint.setText(hintText);

        tabs.getStyleClass().add("popup-tabs");
        tabs.setAlignment(Pos.CENTER_LEFT);
        for (String name : tabNames) {
            ToggleButton button = new ToggleButton(name);
            button.setToggleGroup(tabGroup);
            button.setFocusTraversable(false);
            button.setUserData(name);
            tabs.getChildren().add(button);
        }
        if (!tabNames.isEmpty()) {
            ((ToggleButton) tabs.getChildren().get(0)).setSelected(true);
            currentTab = tabNames.get(0);
            tabGroup.selectedToggleProperty().addListener((o, was, now) -> {
                if (now == null) {
                    was.setSelected(true);
                    return;
                }
                currentTab = (String) now.getUserData();
                refresh();
            });
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            tabs.getChildren().addAll(gap, hint);
            panel.getChildren().add(tabs);
        }

        list.getStyleClass().add("popup-list");
        list.setCellFactory(v -> new ItemCell());
        list.setFocusTraversable(false);
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                choose();
            }
        });
        panel.getChildren().addAll(field, list);
        if (tabNames.isEmpty()) {
            panel.getChildren().add(hint);
        }

        field.textProperty().addListener((o, a, b) -> refresh());
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    move(1);
                    e.consume();
                }
                case UP -> {
                    move(-1);
                    e.consume();
                }
                case PAGE_DOWN -> {
                    move(10);
                    e.consume();
                }
                case PAGE_UP -> {
                    move(-10);
                    e.consume();
                }
                case ENTER -> {
                    choose();
                    e.consume();
                }
                case ESCAPE -> {
                    hide();
                    e.consume();
                }
                case TAB -> {
                    nextTab(e.isShiftDown() ? -1 : 1);
                    e.consume();
                }
                default -> {
                }
            }
        });

        popup.getContent().add(panel);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    private void nextTab(int delta) {
        List<javafx.scene.control.Toggle> toggles = tabGroup.getToggles();
        if (toggles.isEmpty()) {
            return;
        }
        int idx = toggles.indexOf(tabGroup.getSelectedToggle());
        int next = ((idx + delta) % toggles.size() + toggles.size()) % toggles.size();
        toggles.get(next).setSelected(true);
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(size - 1, idx + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(Math.max(0, next - 4));
    }

    private void choose() {
        Item item = list.getSelectionModel().getSelectedItem();
        if (item == null && !list.getItems().isEmpty()) {
            item = list.getItems().get(0);
        }
        if (item == null) {
            return;
        }
        hide();
        try {
            item.onChosen().run();
        } catch (RuntimeException e) {
            System.err.println("smIDE: popup action failed: " + e);
            e.printStackTrace();
        }
    }

    private void refresh() {
        int gen = ++generation;
        provider.query(currentTab, field.getText() == null ? "" : field.getText(), gen, (g, items) -> {
            if (g != generation) {
                return false;
            }
            Platform.runLater(() -> setItems(g, items));
            return true;
        });
    }

    private void setItems(int gen, List<Item> items) {
        if (gen != generation) {
            return;
        }
        list.getItems().setAll(items);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** A popup is its own scene: without this it renders in modena's default palette. */
    public void applyTheme(com.smide.api.ui.Theme theme) {
        if (!panel.getStylesheets().contains(theme.stylesheet())) {
            panel.getStylesheets().add(theme.stylesheet());
        }
        theme.style(panel);
    }

    public void show(Window owner, String initialText) {
        field.setText(initialText == null ? "" : initialText);
        double x = owner.getX() + (owner.getWidth() - 700) / 2;
        double y = owner.getY() + 90;
        popup.show(owner, Math.max(0, x), Math.max(0, y));
        field.requestFocus();
        field.selectAll();
        refresh();
    }

    public void selectTab(String name) {
        for (javafx.scene.control.Toggle t : tabGroup.getToggles()) {
            if (name.equals(t.getUserData())) {
                t.setSelected(true);
            }
        }
    }

    public void hide() {
        popup.hide();
    }

    private static final class ItemCell extends ListCell<Item> {
        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label primary = new Label(item.primary());
            Label secondary = new Label(item.secondary() == null ? "" : item.secondary());
            secondary.getStyleClass().add("popup-item-secondary");
            secondary.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(secondary, Priority.ALWAYS);
            Label shortcut = new Label(item.shortcut() == null ? "" : item.shortcut());
            shortcut.getStyleClass().add("popup-item-shortcut");
            Node icon = Icons.of(item.iconLiteral(), 14);
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            if (icon != null) {
                row.getChildren().add(icon);
            }
            row.getChildren().addAll(primary, secondary, shortcut);
            setText(null);
            setGraphic(row);
        }
    }
}
