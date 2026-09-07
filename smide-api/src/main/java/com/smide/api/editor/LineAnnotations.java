package com.smide.api.editor;

/**
 * A column of text beside each line of an editor - what Git calls blame, and what an
 * IDE calls annotate.
 *
 * <p>The editor asks for one line at a time, only for the lines on screen, so a value
 * has to be cheap to produce: gather the data first, then hand over something that reads
 * from it. Lines are zero-based, and a line past the end of the data should answer with
 * an empty string rather than throwing.
 */
public interface LineAnnotations {

    /** The text for a line, or empty for none. */
    String text(int line);

    /** A fuller explanation on hover, or null. */
    default String tooltip(int line) {
        return null;
    }

    /** Called when the annotation for a line is clicked. */
    default void clicked(int line) {
    }

    /**
     * The widest text the column will ever hold, so it can be sized once.
     *
     * <p>Sizing per line would make the gutter jitter as it scrolls.
     */
    String widest();
}
