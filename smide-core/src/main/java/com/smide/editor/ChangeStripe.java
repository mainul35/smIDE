package com.smide.editor;

import com.smide.api.ui.Tooltips;
import com.smide.vcs.LineChanges;

import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.List;

/**
 * The whole file's changes, in one column beside the scrollbar.
 *
 * <p>The strip against the code says which line changed, and says nothing at all about the
 * thousand lines that are not on screen. In a long file that is the question being asked:
 * not "did this line change" - the line is right there - but "where in this file are the
 * changes, and have I seen them all". IntelliJ answers it with marks down the side of the
 * scrollbar, which is a map of the file rather than of the window.
 *
 * <p>So each stretch of change gets a mark, placed down the stripe in proportion to where
 * it sits in the file, and a click on one takes the editor there. A mark is never thinner
 * than {@link #THINNEST}, because a one-line change in a six-thousand-line file works out
 * at a fifth of a pixel and the point of the thing is that it can be seen.
 *
 * <p>The marks carry the same style classes as the strip beside the code, so a change is
 * the same colour in both places and the theme says what that colour is, once.
 */
final class ChangeStripe extends Pane {

    /** Wide enough to read as a colour rather than a scratch, narrow enough to cost nothing. */
    private static final double WIDTH = 10;

    /** No mark is thinner than this, however short the change. */
    private static final double THINNEST = 3;

    private final org.fxmisc.richtext.CodeArea area;
    private List<LineChanges.Hunk> hunks = List.of();

    ChangeStripe(org.fxmisc.richtext.CodeArea area) {
        this.area = area;
        getStyleClass().add("change-stripe");
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        // Nothing to point at until something has changed.
        setVisible(false);
        setManaged(false);
    }

    /** The stretches to mark. On the FX thread. */
    void setChanges(List<LineChanges.Hunk> hunks) {
        List<LineChanges.Hunk> wanted = hunks == null ? List.of() : List.copyOf(hunks);
        // Asked again after every pause in typing; rebuilding the same marks costs a frame.
        if (wanted.equals(this.hunks)) {
            return;
        }
        this.hunks = wanted;
        getChildren().clear();
        for (LineChanges.Hunk hunk : wanted) {
            getChildren().add(mark(hunk));
        }
        boolean any = !this.hunks.isEmpty();
        setVisible(any);
        setManaged(any);
        requestLayout();
    }

    private Region mark(LineChanges.Hunk hunk) {
        Region mark = new Region();
        mark.getStyleClass().addAll("change-mark", switch (hunk.kind()) {
            case ADDED -> "change-added";
            case CHANGED -> "change-changed";
            case REMOVED -> "change-removed";
        });
        int from = hunk.start() + 1;
        int to = hunk.end();
        String where = hunk.kind() == LineChanges.Kind.REMOVED || to <= from
                ? "line " + from
                : "lines " + from + " to " + to;
        Tooltip.install(mark, Tooltips.of(switch (hunk.kind()) {
            case ADDED -> "Added since the last commit - " + where + ". Click to go there.";
            case CHANGED -> "Changed since the last commit - " + where + ". Click to go there.";
            case REMOVED -> "Lines were removed at " + where + ". Click to go there.";
        }));
        mark.setOnMousePressed(event -> {
            goTo(hunk.start());
            event.consume();
        });
        return mark;
    }

    /** Puts the caret at the start of a change and scrolls it into view. */
    private void goTo(int line) {
        int lines = area.getParagraphs().size();
        int target = Math.max(0, Math.min(lines - 1, line));
        area.moveTo(target, 0);
        area.requestFollowCaret();
        area.requestFocus();
    }

    @Override
    protected void layoutChildren() {
        double height = getHeight();
        int lines = Math.max(1, area.getParagraphs().size());
        for (int i = 0; i < getChildren().size() && i < hunks.size(); i++) {
            LineChanges.Hunk hunk = hunks.get(i);
            double top = height * hunk.start() / lines;
            double covered = Math.max(1, hunk.end() - hunk.start());
            double tall = Math.max(THINNEST, height * covered / lines);
            // A change at the very end of a long file would otherwise be drawn off the end.
            top = Math.min(top, Math.max(0, height - tall));
            getChildren().get(i).resizeRelocate(0, top, WIDTH, tall);
        }
    }
}
