package com.smide.plugins.notebook;

import java.util.Locale;

/**
 * What a cell is, by its {@code cell_type}.
 *
 * <p>nbformat names three and no more. A {@code raw} cell is prose that is not even
 * markdown - it exists so that a notebook converted to LaTeX or reST can carry a block
 * through untouched - and it is rare enough that it gets no editing of its own here, only
 * the same text box a markdown cell gets. It is still a kind, because a notebook that
 * arrives with one must be able to leave with one.
 */
public enum CellKind {
    CODE,
    MARKDOWN,
    RAW;

    /** The name nbformat writes: {@code code}, {@code markdown}, {@code raw}. */
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** What to show a reader. */
    public String displayName() {
        return switch (this) {
            case CODE -> "Code";
            case MARKDOWN -> "Markdown";
            case RAW -> "Raw";
        };
    }

    /**
     * The kind a {@code cell_type} names, or {@link #RAW} for one nothing here knows.
     *
     * <p>nbformat 3 called the prose cell {@code html} as well as {@code markdown}, and
     * called a code cell's language a {@code language} field rather than a kind, so the
     * only unknown kinds that turn up in practice are prose. Treating an unknown kind as
     * raw keeps its text visible and editable instead of hiding it, and - because the kind
     * written back comes from the cell's own JSON unless somebody changes it - the file
     * still says {@code html} after a save.
     */
    public static CellKind parse(String jsonName) {
        if (jsonName == null) {
            return RAW;
        }
        return switch (jsonName.toLowerCase(Locale.ROOT)) {
            case "code" -> CODE;
            case "markdown", "heading", "html" -> MARKDOWN;
            default -> RAW;
        };
    }
}
