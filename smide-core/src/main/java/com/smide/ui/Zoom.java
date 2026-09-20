package com.smide.ui;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;
import javafx.stage.Window;

import java.util.List;

/**
 * Ctrl+plus and Ctrl+minus, applied to the whole window.
 *
 * <p>A scale transform on the scene's content rather than a larger font. A font size
 * grows the text and leaves the icons, the tool stripe, the gutter and every padding
 * where they were, which looks like a bug; a transform scales what is drawn, so a tree
 * row, its disclosure arrow, its icon and its text all grow together and the layout stays
 * in proportion.
 *
 * <p>The content is sized to the window divided by the factor and then scaled back up by
 * it, which is what keeps the layout honest: at 150% the content believes it has
 * two-thirds of the window's width and wraps, ellipsises and lays out accordingly, rather
 * than being drawn oversized and clipped.
 *
 * <p>Every start is {@link #DEFAULT}, and not whatever was left behind last time: a zoom
 * beyond the default is usually a reaction to the moment - a screen share, a projector,
 * tired eyes at the end of the day - and finding the IDE at 200% a week later with no
 * memory of having asked for it is worse than pressing Ctrl+plus twice again.
 *
 * <p>Menus, dialogs and popups are windows of their own with scenes of their own, which
 * a transform on this scene cannot reach. {@link #followEverything} scales those as they
 * open, so a context menu is the same size as the tree it was opened from. Native windows
 * - a file chooser, the desktop's own dialogs - belong to the desktop and are left alone.
 */
public final class Zoom {

    public static final double MIN = 0.5;
    public static final double MAX = 3.0;

    /**
     * Percentage points to a factor: 1.2 is 120%.
     *
     * <p>Named because it appears in both directions - rounding a factor to whole points,
     * and writing one out for the status bar - and a bare 100 in either place is a number
     * whose job has to be worked out from the arithmetic around it.
     */
    private static final double PERCENT = 100;
    /** One press. Ten per cent is small enough to aim with and large enough to notice. */
    public static final double STEP = 0.1;

    /**
     * Where every window starts, and where Ctrl+0 goes back to.
     *
     * <p>120% rather than 100%. The IDE is read for hours at a time on laptop panels
     * whose pixels are smaller than the desktop monitors this was laid out against, and
     * on those the honest size for a tree row is a fifth larger than the toolkit's idea
     * of one. It is a default and not a decision: Ctrl+minus takes it back down in two
     * presses, and one of those is 110%.
     */
    public static final double DEFAULT = 1.2;

    /** What styles a root this makes: the theme, when there is one to ask. */
    private java.util.function.Consumer<Parent> theme;

    /** Given the theme, so a holder this puts round a window's content is styled like everything else. */
    public void styleWith(java.util.function.Consumer<Parent> theme) {
        this.theme = theme;
    }

    private final DoubleProperty factor = new SimpleDoubleProperty(DEFAULT);
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final Pane holder = new Pane();
    private Region content;

    /**
     * Puts {@code content} inside a holder that scales it, and returns the holder to be
     * used as the scene root.
     */
    public Pane wrap(Region content) {
        this.content = content;
        // Unmanaged: the holder would otherwise lay it out at its preferred size, and the
        // size it needs is the window divided by the factor, which only this knows.
        content.setManaged(false);
        content.getTransforms().add(scale);
        holder.getChildren().setAll(content);
        holder.widthProperty().addListener((o, was, now) -> layout());
        holder.heightProperty().addListener((o, was, now) -> layout());
        factor.addListener((o, was, now) -> layout());
        return holder;
    }

    private void layout() {
        if (content == null) {
            return;
        }
        double f = factor.get();
        scale.setX(f);
        scale.setY(f);
        content.resizeRelocate(0, 0, holder.getWidth() / f, holder.getHeight() / f);
    }

    // ------------------------------------------------------- everything else

    /**
     * Scales every other window this application opens, to match this one.
     *
     * <p>A menu, a dialog and a completion list are each a window of their own with a
     * scene of their own, and a transform on this window's scene does not reach them: at
     * 120% the editor grew and its context menu did not, which reads as the menu being
     * broken rather than as two different scales. Rather than finding every place a popup
     * is created - most of them are inside JavaFX's own skins and cannot be reached at all
     * - this watches the list of open windows and scales each one as it appears.
     *
     * <p>Native windows are left alone: a file chooser belongs to the desktop, not to us.
     */
    public void followEverything(Window main) {
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window opened : change.getAddedSubList()) {
                    if (opened != main) {
                        Platform.runLater(() -> scale(opened));
                    }
                }
            }
        });
        factor.addListener((o, was, now) -> {
            for (Window open : List.copyOf(Window.getWindows())) {
                if (open != main) {
                    scale(open);
                }
            }
        });
    }

    /** Puts one window's scene inside a scaling holder, or updates the one it has. */
    private void scale(Window window) {
        Scene scene = window.getScene();
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        if (window instanceof javafx.stage.PopupWindow) {
            scalePopup(window, scene.getRoot());
            return;
        }
        /* A dialog is left at the size the desktop gives it. It sizes its own window from what
           it holds, before the text in it has been laid out and wrapped; scaling that window
           afterwards leaves the layout needing more room than the window has, and the buttons
           end up below its bottom edge, where they cannot be pressed. The zoom is for reading
           code, and a dialog is a few lines and two buttons. */
        if (scene.getRoot() instanceof javafx.scene.control.DialogPane
                || scene.getRoot().lookup(".dialog-pane") != null) {
            return;
        }
        if (scene.getRoot() instanceof ScaledRoot scaled) {
            scaled.setFactor(factor.get());
            resize(window);
            return;
        }
        /* The holder becomes the scene's root, and the root is what the theme puts its class on:
           a window scaled after it was styled ended up with a themed panel inside an unthemed
           root, so whatever the panel did not cover - the strip behind a button bar, the padding
           around a form - stayed the light theme's grey while the text on it was the dark theme's
           near-white. Light text on a light strip is text nobody can read, which is what the
           Compare dialog's heading had become. */
        ScaledRoot holder = new ScaledRoot(scene.getRoot(), factor.get());
        scene.setRoot(holder);
        if (theme != null) {
            theme.accept(holder);
        }
        resize(window);
    }

    /** The transform a popup's content was given, kept on the node so it is added once. */
    private static final String POPUP_SCALE = "smide.zoom.scale";

    /**
     * Scales what a popup holds, leaving its root where the popup put it.
     *
     * <p>A popup sizes its window from its own root, and that root is its to manage. Putting
     * it inside a scaling holder, as a dialog's is, broke that for a ComboBox: its list had
     * not measured itself when the window appeared, the window was sized to the holder's
     * empty preferred size - two pixels by one - and it never grew. Every dropdown in the
     * Settings dialog opened and showed nothing. The transform goes on each piece of content
     * instead, and the popup sizes itself around the scaled content as it always has.
     */
    private void scalePopup(Window window, Parent root) {
        for (Node node : root.getChildrenUnmodifiable()) {
            Object existing = node.getProperties().get(POPUP_SCALE);
            if (existing instanceof Scale transform) {
                transform.setX(factor.get());
                transform.setY(factor.get());
            } else {
                Scale transform = new Scale(factor.get(), factor.get(), 0, 0);
                node.getTransforms().add(transform);
                node.getProperties().put(POPUP_SCALE, transform);
            }
            if (node instanceof Region region) {
                region.setMaxHeight(roomFor(window));
            }
        }
    }

    /**
     * How tall a popup's content may be before it must scroll: the screen, in the content's own
     * unscaled pixels.
     *
     * <p>A menu measures itself and the screen in the same pixels, and decides from the two
     * whether it must scroll. Both halves of that are honest at 100%; scaled up by a fifth the
     * drawing is a fifth taller than what was measured, so a menu that decided it fitted runs
     * off the bottom of the screen - and the run configurations menu, once a project has thirty
     * of them, ran off with Edit Configurations on the far side of the screen's edge, out of
     * reach. Dividing the screen by the factor gives the menu the room it will actually occupy,
     * and one too long for it scrolls, which is what it does unscaled.
     */
    private double roomFor(Window window) {
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen
                .getScreensForRectangle(window.getX(), window.getY(), 1, 1).stream().findFirst()
                .orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        return Math.max(120, Math.floor(screen.getHeight() / factor.get()) - MARGIN);
    }

    /** Room left around a popup that fills the screen, so it does not sit edge to edge. */
    private static final double MARGIN = 24;

    /**
     * Grows the window to fit what is now inside it.
     *
     * <p>A popup has already sized itself to its content by the time it appears, and the
     * content has just become a fifth larger; without this the menu is scaled and then
     * cut off at the old width.
     */
    private static void resize(Window window) {
        if (window instanceof javafx.stage.PopupWindow || window instanceof javafx.stage.Stage) {
            window.sizeToScene();
        }
    }

    /**
     * A scene root that draws its content scaled and reports the scaled size.
     *
     * <p>Reporting the size is the half that matters for a popup: windows size themselves
     * from the root's preferred size, and a transform alone changes what is drawn without
     * changing what is asked for, so the menu would be drawn large inside a window built
     * for the small one.
     */
    private static final class ScaledRoot extends Pane {

        private final Parent content;
        private final Scale transform = new Scale();

        ScaledRoot(Parent content, double factor) {
            this.content = content;
            content.setManaged(false);
            content.getTransforms().add(transform);
            getChildren().setAll(content);
            setFactor(factor);
        }

        void setFactor(double factor) {
            transform.setX(factor);
            transform.setY(factor);
            requestLayout();
            // The parent window asks the scene for its size, which asks this.
            autosize();
        }

        /* Asked in the window's pixels, answered from the content's: the content is laid out at
           the window divided by the factor, so a height asked for a scaled width has to be
           measured at the width the content will really have. Measured at the window's width, a
           wrapping label reports the height of fewer lines than it will take. */
        @Override
        protected double computePrefWidth(double height) {
            return content.prefWidth(unscaled(height)) * transform.getX();
        }

        @Override
        protected double computePrefHeight(double width) {
            return content.prefHeight(unscaled(width)) * transform.getY();
        }

        @Override
        protected double computeMinWidth(double height) {
            return content.minWidth(unscaled(height)) * transform.getX();
        }

        @Override
        protected double computeMinHeight(double width) {
            return content.minHeight(unscaled(width)) * transform.getY();
        }

        private double unscaled(double size) {
            return size < 0 ? size : size / transform.getX();
        }

        @Override
        protected void layoutChildren() {
            double f = transform.getX();
            content.resizeRelocate(0, 0, getWidth() / f, getHeight() / f);
        }
    }

    public void in() {
        set(factor.get() + STEP);
    }

    public void out() {
        set(factor.get() - STEP);
    }

    public void reset() {
        set(DEFAULT);
    }

    /** Clamped, and rounded so repeated steps do not drift to 1.2000000000000002. */
    public void set(double value) {
        double clamped = Math.max(MIN, Math.min(MAX, value));
        factor.set(Math.round(clamped * PERCENT) / PERCENT);
    }

    public double factor() {
        return factor.get();
    }

    public DoubleProperty factorProperty() {
        return factor;
    }

    /** "110%", for the status bar. */
    public String percent() {
        return percent(factor.get());
    }

    /** The same, for a factor nobody is holding - the default, in a message about it. */
    public static String percent(double factor) {
        return Math.round(factor * PERCENT) + "%";
    }
}
