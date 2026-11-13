package com.smide.plugins.notebook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Python a cell is really being written in: itself, with everything above it.
 *
 * <p>A cell is not a file. Its names come from the cells above it, which ran first and left
 * them in one namespace, and a type checker shown one cell on its own reports every one of
 * them as undefined. So what pyright is shown is the concatenation of the code cells from
 * the top of the notebook down to and including the cell being edited - which is exactly
 * the program the reader would have if they ran the notebook to that point - and the
 * answers are mapped back to the cell they belong to.
 *
 * <p>Lines, not characters, are what is mapped. Every cell's source contributes a whole
 * number of lines and its columns are untouched, so a position is a line offset and
 * nothing else; getting that wrong by one is the whole risk in the idea, which is why this
 * class exists apart from any process and is tested on its own.
 *
 * <p>Two things in a notebook are not Python at all. A line beginning with {@code %} or
 * {@code %%} is an IPython magic and a line beginning with {@code !} is a shell command;
 * both are rewritten as comments of the same width, so that the magic does not become a
 * syntax error that buries every real complaint below it, while the lines and columns of
 * everything around it stay where they were.
 */
final class CellContext {

    /** Where a position in the joined text really is. */
    record At(int cellIndex, int line) {
    }

    /** One cell's contribution to the joined text. */
    private record Fragment(int cellIndex, int firstLine, int lineCount) {
    }

    private final String text;
    private final List<Fragment> fragments;
    private final int cellIndex;

    private CellContext(String text, List<Fragment> fragments, int cellIndex) {
        this.text = text;
        this.fragments = fragments;
        this.cellIndex = cellIndex;
    }

    /**
     * Builds the context for one cell.
     *
     * @param cells        the notebook's cells, in order
     * @param throughIndex the index of the cell being edited
     * @param source       what that cell holds right now, which is not what the notebook
     *                     holds: the reader is typing, and completion has to see the half
     *                     word they are standing in
     */
    static CellContext upTo(List<Cell> cells, int throughIndex, String source) {
        StringBuilder text = new StringBuilder();
        List<Fragment> fragments = new ArrayList<>();
        int line = 0;
        for (int i = 0; i <= throughIndex && i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (cell.kind() != CellKind.CODE) {
                // Prose contributes nothing, not even its length: a regular expression or a
                // checker that looks at the start of a line must not meet a sentence.
                continue;
            }
            String body = i == throughIndex && source != null ? source : cell.source();
            String python = pythonOf(body);
            // Every cell ends in a newline whether it was written with one or not, so that
            // the last line of one cell and the first line of the next stay separate lines.
            if (!python.endsWith("\n")) {
                python = python + "\n";
            }
            int lines = countLines(python);
            fragments.add(new Fragment(i, line, lines));
            text.append(python);
            line += lines;
        }
        return new CellContext(text.toString(), List.copyOf(fragments), throughIndex);
    }

    /** The joined Python, as a file for pyright to be shown. */
    String text() {
        return text;
    }

    /** The cell this context was built for. */
    int cellIndex() {
        return cellIndex;
    }

    /** Whether the cell this was built for contributed anything at all. */
    boolean hasCell() {
        return fragments.stream().anyMatch(fragment -> fragment.cellIndex() == cellIndex);
    }

    /**
     * The line in the joined text that a line of a cell became, or -1.
     *
     * <p>-1 for a cell that is not in this context - a markdown cell, or one below the one
     * being edited - because there is no honest answer and a 0 would point at the top of
     * the notebook.
     */
    int lineOf(int cell, int lineInCell) {
        for (Fragment fragment : fragments) {
            if (fragment.cellIndex() == cell) {
                return fragment.firstLine() + Math.max(0, lineInCell);
            }
        }
        return -1;
    }

    /** Where a line of the joined text came from, or empty when it is past the end. */
    Optional<At> at(int line) {
        for (Fragment fragment : fragments) {
            if (line >= fragment.firstLine() && line < fragment.firstLine() + fragment.lineCount()) {
                return Optional.of(new At(fragment.cellIndex(), line - fragment.firstLine()));
            }
        }
        return Optional.empty();
    }

    /** How many lines the joined text has. */
    int lineCount() {
        return fragments.isEmpty() ? 0
                : fragments.get(fragments.size() - 1).firstLine()
                + fragments.get(fragments.size() - 1).lineCount();
    }

    /**
     * A cell's source with its magics and shell lines commented out, same width.
     *
     * <p>Same width is the point: {@code %matplotlib inline} becomes
     * {@code #matplotlib inline}, so the next line is still the next line and a column on
     * this one is still that column. A cell magic ({@code %%timeit}) turns the whole cell
     * into something else, but commenting its first line is still better than leaving a
     * syntax error at the top, and the body below it is usually Python regardless.
     */
    static String pythonOf(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        if (source.indexOf('%') < 0 && source.indexOf('!') < 0 && source.indexOf('?') < 0) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        int from = 0;
        while (from <= source.length()) {
            int end = source.indexOf('\n', from);
            String line = end < 0 ? source.substring(from) : source.substring(from, end);
            out.append(isNotPython(line) ? comment(line) : line);
            if (end < 0) {
                break;
            }
            out.append('\n');
            from = end + 1;
        }
        return out.toString();
    }

    /** Whether a line is a magic, a shell escape or a help query rather than Python. */
    private static boolean isNotPython(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first == '%' || first == '!') {
            return true;
        }
        // "name?" and "name??" ask IPython for help, and are a syntax error anywhere else.
        return trimmed.endsWith("?") && !trimmed.contains(" ") && trimmed.length() > 1;
    }

    /** The line as a comment of exactly the same length. */
    private static String comment(String line) {
        int indent = line.length() - line.stripLeading().length();
        return line.substring(0, indent) + "#" + line.substring(indent + 1);
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
