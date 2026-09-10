package com.smide.ui;

import javafx.animation.PauseTransition;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.util.Duration;

/**
 * Press and hold, for the right click a finger cannot make.
 *
 * <p>Every platform turns a long press into a context menu, and none of them do it for a
 * JavaFX application: a touchscreen sends touch events, JavaFX synthesises a click out of
 * them, and nothing anywhere produces the context-menu request that a right button would.
 * So a laptop with a touchscreen has a file tree that cannot be renamed from, and it looks
 * like the menu is broken rather than unreachable.
 *
 * <p>Both kinds of event are watched. A touchscreen usually delivers real touch events,
 * but where the platform only synthesises mouse events from them - and it says so, with
 * {@link MouseEvent#isSynthesized()} - those work as well. A press that moves more than a
 * few pixels is a scroll and not a hold, which matters on a list: the whole point of the
 * gesture is that it happens while the finger is still down.
 */
public final class LongPress {

    /** Long enough not to fire while scrolling, short enough to feel deliberate. */
    private static final Duration HOLD = Duration.millis(500);

    /** How far a finger may wander and still be holding still. */
    private static final double SLOP = 12;

    /** Where the press was, and what was under it. */
    @FunctionalInterface
    public interface Handler {
        void pressed(Node picked, double screenX, double screenY);
    }

    private LongPress() {
    }

    public static void install(Node node, Handler handler) {
        install(node, HOLD, SLOP, handler);
    }

    public static void install(Node node, Duration hold, double slop, Handler handler) {
        State state = new State(hold, slop, handler);
        node.addEventFilter(TouchEvent.TOUCH_PRESSED, e -> {
            TouchPoint point = e.getTouchPoint();
            state.begin(point.getScreenX(), point.getScreenY(), picked(point.getTarget()));
        });
        node.addEventFilter(TouchEvent.TOUCH_MOVED, e ->
                state.moved(e.getTouchPoint().getScreenX(), e.getTouchPoint().getScreenY()));
        node.addEventFilter(TouchEvent.TOUCH_RELEASED, e -> state.cancel());
        node.addEventFilter(TouchEvent.TOUCH_STATIONARY, e -> { });

        // The same gesture where the platform hands over mouse events instead.
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.isSynthesized()) {
                state.begin(e.getScreenX(), e.getScreenY(), picked(e.getTarget()));
            }
        });
        node.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> state.moved(e.getScreenX(), e.getScreenY()));
        node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> state.cancel());
    }

    private static Node picked(EventTarget target) {
        return target instanceof Node node ? node : null;
    }

    /** One gesture in progress. */
    private static final class State {

        private final double slop;
        private final Handler handler;
        private final PauseTransition timer;
        private double x;
        private double y;
        private Node target;

        State(Duration hold, double slop, Handler handler) {
            this.slop = slop;
            this.handler = handler;
            this.timer = new PauseTransition(hold);
            this.timer.setOnFinished(e -> handler.pressed(target, x, y));
        }

        void begin(double screenX, double screenY, Node picked) {
            x = screenX;
            y = screenY;
            target = picked;
            timer.playFromStart();
        }

        void moved(double screenX, double screenY) {
            if (Math.abs(screenX - x) > slop || Math.abs(screenY - y) > slop) {
                cancel();
            }
        }

        void cancel() {
            timer.stop();
        }
    }
}
