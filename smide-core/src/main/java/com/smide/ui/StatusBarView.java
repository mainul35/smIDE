package com.smide.ui;

import com.smide.api.ui.StatusBar;
import com.smide.api.ui.StatusBarWidget;
import com.smide.api.ui.Theme;
import com.smide.core.ExtensionRegistry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The strip along the bottom: a transient message on the left, background progress in
 * the middle, and on the right the facts about the current editor plus whatever widgets
 * plugins add. Clicking the progress opens what every background task is doing.
 */
public final class StatusBarView extends HBox implements StatusBar {

    /** Finished tasks the popup still shows, newest first. */
    private static final int KEEP_FINISHED = 10;

    private final Label message = new Label();
    private final HBox progressBox = new HBox(6);
    private final Label progressLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(-1);
    private final Label more = new Label();
    private final HBox right = new HBox(14);
    private final HBox editorFacts = new HBox(14);
    private final PauseTransition clearMessage = new PauseTransition(Duration.seconds(6));
    private final List<BackgroundTask> running = new ArrayList<>();
    private final ArrayDeque<BackgroundTask> finished = new ArrayDeque<>();
    private final BackgroundTasksPopup tasks;
    /** What the progress area was last built for, so a line of output only changes its text. */
    private BackgroundTask shownTask;
    private int shownCount;
    private double shownFraction = -2;

    public StatusBarView(ExtensionRegistry registry, Theme theme) {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        progressBox.getStyleClass().add("status-progress");
        progressBox.setAlignment(Pos.CENTER_LEFT);
        progressLabel.setMaxWidth(320);
        right.setAlignment(Pos.CENTER_RIGHT);
        editorFacts.setAlignment(Pos.CENTER_RIGHT);
        Region gap = new Region();
        gap.setMinWidth(10);
        getChildren().addAll(message, progressBox, gap, editorFacts, right);
        clearMessage.setOnFinished(e -> message.setText(""));

        tasks = new BackgroundTasksPopup(theme, () -> List.copyOf(running), () -> List.copyOf(finished));
        progressBox.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                tasks.toggle(progressBox);
            }
        });
        Tooltip.install(progressBox, com.smide.api.ui.Tooltips.of("Show what the background tasks are doing"));

        for (StatusBarWidget w : registry.statusWidgets()) {
            addWidget(w);
        }
        registry.onStatusWidgetAdded(this::addWidget);
    }

    private void addWidget(StatusBarWidget widget) {
        Node node = widget.node();
        node.getStyleClass().add("status-widget");
        right.getChildren().add(node);
    }

    /** The area for caret position, encoding and the like. */
    public HBox editorFacts() {
        return editorFacts;
    }

    /** Opens the background tasks popup, or closes it. */
    public void toggleTasks() {
        tasks.toggle(progressBox);
    }

    @Override
    public void message(String text) {
        Platform.runLater(() -> {
            message.setText(text == null ? "" : text);
            clearMessage.playFromStart();
        });
    }

    @Override
    public Progress progress(String title, boolean cancellable) {
        BackgroundTask task = new BackgroundTask(title, cancellable, this::refreshProgress, this::finished);
        Platform.runLater(() -> {
            running.add(task);
            refreshProgress();
        });
        return task;
    }

    private void finished(BackgroundTask task) {
        running.remove(task);
        finished.addFirst(task);
        while (finished.size() > KEEP_FINISHED) {
            finished.removeLast();
        }
        refreshProgress();
        if (tasks.isShowing()) {
            tasks.refresh();
        }
    }

    private void refreshProgress() {
        if (running.isEmpty()) {
            progressBox.getChildren().clear();
            shownTask = null;
            return;
        }
        BackgroundTask first = running.get(0);
        progressLabel.setText(first.message().isEmpty() ? first.title : first.title + ": " + first.message());
        double fraction = first.fraction() < 0 ? -1 : Math.min(1, first.fraction());
        if (fraction != shownFraction) {
            // Setting the same indeterminate value again restarts its animation.
            progressBar.setProgress(fraction);
            shownFraction = fraction;
        }
        if (first == shownTask && running.size() == shownCount) {
            return;
        }
        shownTask = first;
        shownCount = running.size();
        progressBox.getChildren().setAll(progressLabel, progressBar);
        if (first.cancellable) {
            progressBox.getChildren().add(Icons.button("fth-x", "Cancel", first::cancel));
        }
        if (running.size() > 1) {
            more.setText("+" + (running.size() - 1));
            progressBox.getChildren().add(more);
        }
    }

    /** For tests: the text beside the progress bar, empty when nothing runs. */
    String progressText() {
        return progressBox.getChildren().isEmpty() ? "" : progressLabel.getText();
    }

    /** For tests. */
    BackgroundTasksPopup tasksPopup() {
        return tasks;
    }
}
