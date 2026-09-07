package com.smide.ui;

import com.smide.api.ui.Notifications;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Balloons in the top-right corner of the editor area. Information fades after a few
 * seconds; warnings and errors stay until dismissed. Everything is also kept for the
 * Notifications tool window.
 */
public final class NotificationCenter implements Notifications {

    private final VBox stack = new VBox();
    private final List<Notification> history = new ArrayList<>();
    private final List<Consumer<Notification>> listeners = new ArrayList<>();

    public NotificationCenter() {
        stack.getStyleClass().add("notification-stack");
        stack.setPickOnBounds(false);
        stack.setAlignment(Pos.TOP_RIGHT);
        stack.setMaxWidth(Region.USE_PREF_SIZE);
        stack.setMaxHeight(Region.USE_PREF_SIZE);
    }

    /** Overlay this on top of the editor area. */
    public VBox node() {
        return stack;
    }

    public void addListener(Consumer<Notification> listener) {
        listeners.add(listener);
    }

    @Override
    public void info(String title, String message, NotificationAction... actions) {
        post(Level.INFO, title, message, actions);
    }

    @Override
    public void warn(String title, String message, NotificationAction... actions) {
        post(Level.WARNING, title, message, actions);
    }

    @Override
    public void error(String title, String message, NotificationAction... actions) {
        post(Level.ERROR, title, message, actions);
    }

    /** Long enough to say what went wrong, short enough to stay a balloon. */
    private static final int MAX_MESSAGE = 420;

    private void post(Level level, String title, String message, NotificationAction... actions) {
        Notification n = new Notification(System.currentTimeMillis(), level, title, shorten(message),
                actions == null ? List.of() : List.of(actions));
        Platform.runLater(() -> {
            history.add(0, n);
            if (history.size() > 200) {
                history.remove(history.size() - 1);
            }
            listeners.forEach(l -> l.accept(n));
            show(n);
        });
    }

    private void show(Notification n) {
        VBox box = new VBox(4);
        box.getStyleClass().addAll("notification", "notification-" + n.level().name().toLowerCase(Locale.ROOT));
        Label title = new Label(n.title());
        title.getStyleClass().add("notification-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(6, title, gap, Icons.button("fth-x", "Dismiss", () -> stack.getChildren().remove(box)));
        head.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(head);
        if (n.message() != null && !n.message().isBlank()) {
            Label msg = new Label(n.message());
            msg.getStyleClass().add("notification-message");
            msg.setWrapText(true);
            msg.setMaxWidth(340);
            box.getChildren().add(msg);
        }
        if (!n.actions().isEmpty()) {
            HBox links = new HBox(4);
            for (NotificationAction a : n.actions()) {
                Hyperlink link = new Hyperlink(a.label());
                link.setOnAction(e -> {
                    stack.getChildren().remove(box);
                    a.action().run();
                });
                links.getChildren().add(link);
            }
            box.getChildren().add(links);
        }
        stack.getChildren().add(box);
        while (stack.getChildren().size() > 4) {
            stack.getChildren().remove(0);
        }
        if (n.level() == Level.INFO) {
            PauseTransition fade = new PauseTransition(Duration.seconds(7));
            fade.setOnFinished(e -> stack.getChildren().remove(box));
            fade.play();
        }
    }

    /**
     * Keeps the first lines that carry the message and drops the rest.
     *
     * <p>A failed build arrives as sixty lines of Maven output; shown whole it covered the
     * editor. The console has the full text, so the balloon only has to say enough to
     * recognise the failure.
     */
    private static String shorten(String message) {
        if (message == null) {
            return "";
        }
        String text = message.strip();
        if (text.length() <= MAX_MESSAGE) {
            return text;
        }
        int cut = text.lastIndexOf('\n', MAX_MESSAGE);
        return text.substring(0, cut > 120 ? cut : MAX_MESSAGE).strip() + "\n...";
    }

    @Override
    public List<Notification> history() {
        return List.copyOf(history);
    }
}
