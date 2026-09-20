package com.smide.api.ui;

import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * Tooltips that stay while they are being read.
 *
 * <p>JavaFX takes a tooltip away after five seconds whether or not the pointer is still on the
 * thing it explains, which on a small target - a three-pixel strip beside the code, an icon in the
 * gutter - is short enough to read as a flicker. The stylesheet says the same for every tooltip
 * the IDE shows; this is for the ones built in code that want it said outright.
 */
public final class Tooltips {

    private Tooltips() {
    }

    /** Shown a quarter of a second after the pointer arrives, and kept while it stays. */
    public static Tooltip stay(Tooltip tooltip) {
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setShowDuration(Duration.hours(1));
        tooltip.setHideDelay(Duration.millis(150));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }

    /** A tooltip with this text, that stays. */
    public static Tooltip of(String text) {
        return stay(new Tooltip(text));
    }
}
