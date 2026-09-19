package com.smide.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the window shows before anything is open: the three ways to start, and the
 * folders that were open last. Deliberately plain - it is the first thing seen and the
 * least often seen.
 */
public final class WelcomeView extends VBox {

    public record ActionRow(String text, String shortcut, Runnable onChosen) {
    }

    private final VBox recentList = new VBox(2);
    private final VBox recentSection;
    private final Consumer<Path> onOpenRecent;

    public WelcomeView(String version, List<ActionRow> actions, Consumer<Path> onOpenRecent) {
        this.onOpenRecent = onOpenRecent;
        getStyleClass().add("welcome");

        Label title = new Label("smIDE");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("Version " + version);
        subtitle.getStyleClass().add("welcome-subtitle");
        VBox heading = new VBox(2, title, subtitle);
        heading.setAlignment(Pos.CENTER);

        VBox actionBox = new VBox(2);
        for (ActionRow row : actions) {
            actionBox.getChildren().add(action(row));
        }

        Label recentHeading = new Label("RECENT WORKSPACES");
        recentHeading.getStyleClass().add("welcome-section");
        recentSection = new VBox(6, recentHeading, recentList);

        VBox card = new VBox(22, heading, actionBox, recentSection);
        card.getStyleClass().add("welcome-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(480);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        /* In a scroll pane, centred while it fits: with a few recent workspaces on a small
           screen the card is taller than the space, and a view that will not shrink below its
           card pushed the whole window's layout out of the window - the menu cut off at the
           top, the status bar at the bottom. */
        VBox holder = new VBox(card);
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(40));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("welcome-scroll");
        scroll.viewportBoundsProperty().addListener((o, was, now) -> holder.setMinHeight(now.getHeight()));
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        setMinHeight(0);
        getChildren().add(scroll);
    }

    private static HBox action(ActionRow row) {
        Hyperlink link = new Hyperlink(row.text());
        link.getStyleClass().add("welcome-action");
        link.setFocusTraversable(false);
        link.setOnAction(e -> row.onChosen().run());
        Label keys = new Label(row.shortcut() == null ? "" : row.shortcut());
        keys.getStyleClass().add("welcome-shortcut");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox box = new HBox(12, link, gap, keys);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public void setRecent(List<Path> roots) {
        recentList.getChildren().clear();
        boolean any = roots != null && !roots.isEmpty();
        recentSection.setVisible(any);
        recentSection.setManaged(any);
        if (!any) {
            return;
        }
        for (Path root : roots.subList(0, Math.min(roots.size(), 7))) {
            Hyperlink link = new Hyperlink(root.getFileName() == null ? root.toString() : root.getFileName().toString());
            link.getStyleClass().add("welcome-recent-name");
            link.setFocusTraversable(false);
            link.setOnAction(e -> onOpenRecent.accept(root));
            Label where = new Label(root.getParent() == null ? "" : root.getParent().toString());
            where.getStyleClass().add("welcome-recent-path");
            link.setMinWidth(Region.USE_PREF_SIZE);
            where.setMinWidth(0);
            where.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(where, Priority.ALWAYS);
            HBox row = new HBox(10, link, where);
            row.setAlignment(Pos.BASELINE_LEFT);
            recentList.getChildren().add(row);
        }
    }
}
