package com.smide.plugins.assistant;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * A status line that counts while something is being waited for.
 *
 * <p>"Marking..." that never changes is indistinguishable from a button that did nothing,
 * and that is what somebody reports: the request does not even go through. A model behind
 * a proxy can take a minute to say its first word - longer when it is being loaded - and
 * for that minute the panel has to keep saying that it is still there.
 *
 * <p>After a while it says the other half out loud, because by then the question in the
 * reader's mind is no longer "is it working" but "is this normal".
 */
final class Waiting {

    /** When a wait stops being ordinary and is worth explaining. */
    private static final int EXPLAIN_AFTER = 20;

    private final Label label;
    private Timeline ticker;
    private String what = "";
    private long since;

    Waiting(Label label) {
        this.label = label;
    }

    /** Starts counting, with {@code what} in front of the seconds. */
    void start(String what) {
        stop();
        this.what = what;
        this.since = System.currentTimeMillis();
        show();
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> show()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    /** Stops counting and leaves the label alone. */
    void stop() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    /** Stops counting and says how it ended. */
    void stop(String text) {
        stop();
        label.setText(text);
    }

    /** How long the current wait has been going, in seconds. */
    int seconds() {
        return (int) ((System.currentTimeMillis() - since) / 1000);
    }

    private void show() {
        int seconds = seconds();
        String text = what + "...  " + seconds + "s";
        if (seconds >= EXPLAIN_AFTER) {
            text += "  -  a large model can take a while to answer the first question"
                    + " while it is being loaded.";
        }
        label.setText(text);
    }
}
