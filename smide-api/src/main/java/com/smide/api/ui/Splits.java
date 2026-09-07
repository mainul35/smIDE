package com.smide.api.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;

/**
 * Gives a split pane's dividers enough padding to be grabbed with a mouse.
 *
 * <p>A divider is a node the stylesheet sizes, and for the split panes built while the
 * window is being put together that is the end of it. A divider created later - a tool
 * window opened from the stripe, a split inside one - comes up with no padding at all,
 * which for a horizontal bar means no height: it is there, but there is nothing to see
 * and nothing to drag. Setting the padding on the nodes themselves removes the
 * difference. The numbers are the ones in the stylesheet, so it does not matter which
 * of the two ends up applying.
 *
 * <p>Call it on any split pane you build; it costs nothing on the ones that were fine.
 */
public final class Splits {

    /** Half the grab width, as padding on each side of the line. */
    private static final double GRAB = 3;

    private Splits() {
    }

    /** Sizes the dividers now, after the next pulse, and whenever the items change. */
    public static void grabbable(SplitPane pane) {
        Runnable apply = () -> size(pane);
        apply.run();
        Platform.runLater(apply);
        pane.getItems().addListener((ListChangeListener<Node>) change -> Platform.runLater(apply));
    }

    private static void size(SplitPane pane) {
        // All four sides, because a split pane measures a divider's thickness the same
        // way whichever way the divider runs; see the note in smide.css.
        Insets insets = new Insets(GRAB);
        for (Node node : pane.lookupAll(".split-pane-divider")) {
            if (node instanceof Region region && region.getParent() == pane) {
                region.setPadding(insets);
            }
        }
    }
}
