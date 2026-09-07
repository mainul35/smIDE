package com.smide.ui;

import com.smide.api.ui.StatusBar;
import com.smide.api.ui.StatusBarWidget;
import com.smide.core.ExtensionRegistry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The strip along the bottom: a transient message on the left, background progress in
 * the middle, and on the right the facts about the current editor plus whatever widgets
 * plugins add.
 */
public final class StatusBarView extends HBox implements StatusBar {

    private final Label message = new Label();
    private final HBox progressBox = new HBox(6);
    private final HBox right = new HBox(14);
    private final HBox editorFacts = new HBox(14);
    private final PauseTransition clearMessage = new PauseTransition(Duration.seconds(6));
    private final List<ProgressImpl> progresses = new ArrayList<>();

    public StatusBarView(ExtensionRegistry registry) {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        progressBox.getStyleClass().add("status-progress");
        progressBox.setAlignment(Pos.CENTER_LEFT);
        right.setAlignment(Pos.CENTER_RIGHT);
        editorFacts.setAlignment(Pos.CENTER_RIGHT);
        Region gap = new Region();
        gap.setMinWidth(10);
        getChildren().addAll(message, progressBox, gap, editorFacts, right);
        clearMessage.setOnFinished(e -> message.setText(""));

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

    @Override
    public void message(String text) {
        Platform.runLater(() -> {
            message.setText(text == null ? "" : text);
            clearMessage.playFromStart();
        });
    }

    @Override
    public Progress progress(String title, boolean cancellable) {
        ProgressImpl p = new ProgressImpl(title, cancellable);
        Platform.runLater(() -> {
            progresses.add(p);
            refreshProgress();
        });
        return p;
    }

    private void refreshProgress() {
        progressBox.getChildren().clear();
        if (progresses.isEmpty()) {
            return;
        }
        ProgressImpl first = progresses.get(0);
        Label label = new Label(first.text);
        label.setMaxWidth(320);
        progressBox.getChildren().addAll(label, first.bar);
        if (first.cancellable) {
            progressBox.getChildren().add(Icons.button("fth-x", "Cancel", first::cancel));
        }
        if (progresses.size() > 1) {
            progressBox.getChildren().add(new Label("+" + (progresses.size() - 1)));
        }
    }

    private final class ProgressImpl implements Progress {
        final String title;
        final boolean cancellable;
        final ProgressBar bar = new ProgressBar(-1);
        volatile String text;
        volatile boolean cancelled;
        Consumer<Progress> onCancel;

        ProgressImpl(String title, boolean cancellable) {
            this.title = title;
            this.cancellable = cancellable;
            this.text = title;
        }

        @Override
        public void update(String message, double fraction) {
            Platform.runLater(() -> {
                text = message == null || message.isBlank() ? title : title + ": " + message;
                bar.setProgress(fraction < 0 ? -1 : Math.min(1, fraction));
                refreshProgress();
            });
        }

        @Override
        public void done() {
            Platform.runLater(() -> {
                progresses.remove(this);
                refreshProgress();
            });
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onCancel(Consumer<Progress> handler) {
            this.onCancel = handler;
        }

        void cancel() {
            cancelled = true;
            if (onCancel != null) {
                onCancel.accept(this);
            }
            done();
        }
    }
}
