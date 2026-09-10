package com.smide.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

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
 * <p>Windows the IDE opens for itself, and popup menus, are separate scenes and are not
 * scaled by this.
 */
public final class Zoom {

    public static final double MIN = 0.5;
    public static final double MAX = 3.0;
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
        factor.set(Math.round(clamped * 100) / 100.0);
    }

    public double factor() {
        return factor.get();
    }

    public DoubleProperty factorProperty() {
        return factor;
    }

    /** "110%", for the status bar. */
    public String percent() {
        return Math.round(factor.get() * 100) + "%";
    }
}
