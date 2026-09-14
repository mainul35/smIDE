package com.smide.ui;

import com.smide.api.ui.Theme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the background tasks are doing: opened by clicking the progress in the status bar.
 *
 * <p>Each task shows how long it has been going, the line it is on, and everything it has
 * printed so far, following along as more arrives - a {@code go install} fetching modules,
 * Maven resolving dependencies, pip unpacking a wheel. Tasks that finished while it was
 * open stay in it, under their running time, so the last lines of one that just ended can
 * still be read.
 */
final class BackgroundTasksPopup {

    private static final double WIDTH = 580;

    private final Popup popup = new Popup();
    private final VBox rows = new VBox();
    private final Label empty = new Label("Nothing is running.");
    private final Supplier<List<BackgroundTask>> running;
    private final Supplier<List<BackgroundTask>> finished;
    private final Map<BackgroundTask, Row> shown = new IdentityHashMap<>();
    private List<BackgroundTask> order = List.of();
    /** While open, redraws what changed; output arriving a line at a time is drawn in batches. */
    private final Timeline tick = new Timeline(new KeyFrame(Duration.millis(250), e -> refresh()));

    BackgroundTasksPopup(Theme theme, Supplier<List<BackgroundTask>> running, Supplier<List<BackgroundTask>> finished) {
        this.running = running;
        this.finished = finished;
        Label heading = new Label("Background tasks");
        heading.getStyleClass().add("background-tasks-heading");
        empty.getStyleClass().add("background-task-meta");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(WIDTH);
        scroll.setMaxHeight(520);
        VBox box = new VBox(6, heading, empty, scroll);
        box.getStyleClass().add("background-tasks");
        box.setPrefWidth(WIDTH + 24);
        popup.getContent().add(box);
        theme.style(popup);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setAutoFix(true);
        // Placed by its bottom right corner, just above the progress it was opened from.
        popup.setAnchorLocation(PopupWindow.AnchorLocation.WINDOW_BOTTOM_RIGHT);
        tick.setCycleCount(Timeline.INDEFINITE);
        popup.setOnShown(e -> tick.play());
        popup.setOnHidden(e -> tick.stop());
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    void toggle(Region anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (anchor.getScene() == null) {
            return;
        }
        refresh();
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor.getScene().getWindow(), bounds.getMaxX(), bounds.getMinY() - 4);
    }

    /** Draws the tasks as they are now. */
    void refresh() {
        List<BackgroundTask> now = running.get();
        List<BackgroundTask> done = finished.get();
        List<BackgroundTask> all = new ArrayList<>(now);
        all.addAll(done);
        if (!all.equals(order)) {
            order = all;
            shown.keySet().retainAll(new java.util.HashSet<>(all));
            rows.getChildren().clear();
            for (BackgroundTask task : now) {
                rows.getChildren().add(shown.computeIfAbsent(task, Row::new).node);
            }
            if (!done.isEmpty()) {
                Label section = new Label("Finished");
                section.getStyleClass().add("background-tasks-section");
                rows.getChildren().add(section);
                for (BackgroundTask task : done) {
                    rows.getChildren().add(shown.computeIfAbsent(task, Row::new).node);
                }
            }
        }
        empty.setVisible(all.isEmpty());
        empty.setManaged(all.isEmpty());
        for (Row row : shown.values()) {
            row.update();
        }
    }

    static String duration(long millis) {
        long seconds = millis / 1000;
        return seconds < 60 ? seconds + " s" : String.format("%d min %02d s", seconds / 60, seconds % 60);
    }

    private static Region gap() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    /** One task: its title and time, its bar and current line, and its output. */
    private final class Row {
        final BackgroundTask task;
        final VBox node;
        final Label meta = new Label();
        final ProgressBar bar = new ProgressBar(-1);
        final Label line = new Label();
        final Hyperlink toggle = new Hyperlink();
        final TextArea output = new TextArea();
        final Button cancel;
        boolean expanded;
        /** Lines of the task's output already in the text area. */
        long drawn;
        double drawnFraction = -1;

        Row(BackgroundTask task) {
            this.task = task;
            Label title = new Label(task.title);
            title.getStyleClass().add("background-task-title");
            meta.getStyleClass().add("background-task-meta");
            cancel = Icons.button("fth-x", "Cancel", task::cancel);
            HBox head = new HBox(8, title, gap(), meta);
            head.setAlignment(Pos.CENTER_LEFT);
            if (task.cancellable) {
                head.getChildren().add(cancel);
            }
            bar.setMaxWidth(Double.MAX_VALUE);
            line.getStyleClass().add("background-task-meta");
            line.setMaxWidth(WIDTH);
            output.setEditable(false);
            output.setWrapText(false);
            output.setPrefRowCount(12);
            output.getStyleClass().add("background-task-output");
            // One family that exists, as the editor does: JavaFX takes only the first of a CSS list.
            output.setStyle("-fx-font-family: \"" + Fonts.monospace() + "\";");
            toggle.setOnAction(e -> {
                expanded = !expanded;
                if (expanded) {
                    drawn = 0;
                    output.clear();
                }
                update();
            });
            // A running task is opened to show its output: seeing what it is doing is why one clicks.
            expanded = task.isRunning();
            node = new VBox(4, head, bar, line, toggle, output);
            node.getStyleClass().add("background-task");
            update();
        }

        void update() {
            boolean running = task.isRunning();
            String time = duration(task.elapsedMillis());
            meta.setText(running ? time : (task.wasCancelled() ? "cancelled after " : "finished in ") + time);
            bar.setVisible(running);
            bar.setManaged(running);
            double fraction = task.fraction() < 0 ? -1 : Math.min(1, task.fraction());
            if (fraction != drawnFraction) {
                // Setting the same indeterminate value again restarts its animation.
                bar.setProgress(fraction);
                drawnFraction = fraction;
            }
            cancel.setVisible(running);
            String message = task.message();
            line.setText(message.isEmpty() ? (running ? "Working..." : "") : message);
            line.setVisible(running);
            line.setManaged(running);
            long count = task.lineCount();
            toggle.setText(count == 0 ? "No output" + (running ? " yet" : "")
                    : (expanded ? "Hide output" : "Show output") + " (" + count + (count == 1 ? " line)" : " lines)"));
            toggle.setDisable(count == 0);
            boolean open = expanded && count > 0;
            output.setVisible(open);
            output.setManaged(open);
            // As tall as its output, up to twelve lines: a task that printed one line has no use for a dozen empty ones.
            output.setPrefRowCount((int) Math.max(2, Math.min(12, count)));
            if (open && count > drawn) {
                append(count);
            }
        }

        private void append(long count) {
            boolean following = output.getCaretPosition() >= output.getLength();
            if (drawn == 0 || !task.keepsLinesSince(drawn)) {
                output.setText(String.join("\n", task.lastLines(BackgroundTask.MAX_LINES)));
            } else {
                output.appendText("\n" + String.join("\n", task.lastLines(count - drawn)));
            }
            drawn = count;
            if (following) {
                output.positionCaret(output.getLength());
                output.setScrollTop(Double.MAX_VALUE);
            }
        }
    }

    /** For tests: the node showing a task, if it is shown. */
    Node rowOf(BackgroundTask task) {
        Row row = shown.get(task);
        return row == null ? null : row.node;
    }

    /** For tests: everything the popup shows. */
    Node content() {
        return popup.getContent().get(0);
    }
}
