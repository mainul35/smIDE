package com.smide.plugins.assistant;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/**
 * A status line that counts, and turns, while something is being waited for.
 *
 * <p>"Marking..." that never changes is indistinguishable from a button that did nothing,
 * and that is what somebody reports: the request does not even go through. A model behind
 * a proxy can take a minute to say its first word - longer when it is being loaded - and
 * for that minute the panel has to keep saying that it is still there.
 *
 * <p>The seconds say it once a second; a turning wheel says it continuously. The two
 * answer different questions - how long has this been, and is anything happening at all -
 * and a number that changes only on the second is a poor answer to the second of them.
 *
 * <p>After a while it says the other half out loud, because by then the question in the
 * reader's mind is no longer "is it working" but "is this normal".
 */
final class Waiting {

    /** When a wait stops being ordinary and is worth explaining. */
    private static final int EXPLAIN_AFTER = 20;

    /** The wheel: small enough to sit on a line of 11px text without crowding it. */
    private static final double RADIUS = 5;
    private static final double THICKNESS = 2;

    /** Three quarters of a circle, so there is a gap and the turning can be seen. */
    private static final double SWEEP = 270;

    /** One turn a second: fast enough to read as alive, slow enough not to nag. */
    private static final Duration TURN = Duration.seconds(1);

    private final Label label;
    private final Arc wheel = wheel();
    private final RotateTransition turning = turning(wheel);
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
        label.setGraphic(wheel);
        wheel.setRotate(0);
        turning.playFromStart();
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> show()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    /** Stops counting and leaves the label's words alone. */
    void stop() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
        turning.stop();
        // The wheel goes with the waiting: one left turning beside a finished sentence
        // would be saying something is still happening when nothing is.
        if (label.getGraphic() == wheel) {
            label.setGraphic(null);
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

    /** The turning wheel, for a test to look at; it is on the label only while waiting. */
    Node spinner() {
        return wheel;
    }

    /** Whether something is being waited for right now. */
    boolean running() {
        return ticker != null;
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

    /** An open arc rather than a ring: a ring that turns looks exactly like one that does not. */
    private static Arc wheel() {
        Arc arc = new Arc(RADIUS, RADIUS, RADIUS, RADIUS, 90, SWEEP);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStrokeWidth(THICKNESS);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        arc.getStyleClass().add("waiting-wheel");
        // A colour to fall back on: an author stylesheet still overrides it, and without
        // one a shape with no stroke is a shape nobody can see.
        arc.setStroke(Color.web("#4a9eff"));
        return arc;
    }

    private static RotateTransition turning(Arc arc) {
        RotateTransition spin = new RotateTransition(TURN, arc);
        spin.setByAngle(360);
        // Linear, because a wheel that eases is a wheel that keeps appearing to stall.
        spin.setInterpolator(Interpolator.LINEAR);
        spin.setCycleCount(Animation.INDEFINITE);
        return spin;
    }
}
