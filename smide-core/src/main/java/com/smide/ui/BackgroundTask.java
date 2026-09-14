package com.smide.ui;

import com.smide.api.ui.StatusBar;
import javafx.application.Platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * A background task shown in the status bar, and everything it has said while running.
 *
 * <p>The status bar has room for one line, so a task that printed a hundred - a {@code go
 * install}, a Maven build, a pip install - used to show only the last of them, and nothing
 * at all once it had finished. Each line is kept here instead, for the background tasks
 * popup to show. State changes happen on the JavaFX thread; {@link #update} and
 * {@link #done} may be called from any thread.
 */
final class BackgroundTask implements StatusBar.Progress {

    /** Lines kept per task; the oldest go first. */
    static final int MAX_LINES = 2000;

    final String title;
    final boolean cancellable;
    final long startedAt = System.currentTimeMillis();
    private final Runnable changed;
    private final Consumer<BackgroundTask> finished;

    // JavaFX thread only.
    private String message = "";
    private double fraction = -1;
    private long endedAt;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long lineCount;
    private String lastLine;

    private volatile boolean cancelled;
    private volatile Consumer<StatusBar.Progress> onCancel;

    /**
     * @param changed  told after anything about the task changes
     * @param finished told once, when it is done or cancelled
     */
    BackgroundTask(String title, boolean cancellable, Runnable changed, Consumer<BackgroundTask> finished) {
        this.title = title;
        this.cancellable = cancellable;
        this.changed = changed;
        this.finished = finished;
    }

    @Override
    public void update(String text, double fraction) {
        Platform.runLater(() -> {
            if (endedAt != 0) {
                return;
            }
            String line = text == null ? "" : text.stripTrailing();
            this.message = line.strip();
            this.fraction = fraction;
            if (!line.isBlank() && !line.equals(lastLine)) {
                lastLine = line;
                lines.addLast(line);
                lineCount++;
                if (lines.size() > MAX_LINES) {
                    lines.removeFirst();
                }
            }
            changed.run();
        });
    }

    @Override
    public void done() {
        Platform.runLater(() -> {
            if (endedAt != 0) {
                return;
            }
            endedAt = System.currentTimeMillis();
            finished.accept(this);
        });
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onCancel(Consumer<StatusBar.Progress> handler) {
        this.onCancel = handler;
    }

    void cancel() {
        cancelled = true;
        Consumer<StatusBar.Progress> handler = onCancel;
        if (handler != null) {
            handler.accept(this);
        }
        done();
    }

    boolean isRunning() {
        return endedAt == 0;
    }

    boolean wasCancelled() {
        return cancelled;
    }

    /** The last thing it said, or empty. */
    String message() {
        return message;
    }

    /** Between 0 and 1, or negative when it cannot tell. */
    double fraction() {
        return fraction;
    }

    long elapsedMillis() {
        return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
    }

    /** How many lines it has said in all, including those no longer kept. */
    long lineCount() {
        return lineCount;
    }

    /** The last {@code count} lines kept, oldest first; all of them when fewer are kept. */
    List<String> lastLines(long count) {
        List<String> out = new ArrayList<>();
        Iterator<String> newestFirst = lines.descendingIterator();
        while (newestFirst.hasNext() && out.size() < count) {
            out.add(newestFirst.next());
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Whether the lines since {@code seen} are all still kept. */
    boolean keepsLinesSince(long seen) {
        return lineCount - seen <= lines.size();
    }
}
