package com.smide.plugins.langgraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Python in a Jupyter notebook, as one text.
 *
 * <p>A graph written in a notebook is written across cells: the imports in one, the nodes in
 * the next, the edges and the compile below that. Nothing that reads a graph cares where the
 * cell boundaries are - the calls are the same calls - so the code cells are joined and read
 * as one file, which is also how the notebook itself behaves when it is run top to bottom.
 *
 * <p>Markdown cells are left out, and so is their length: the joined text is Python and
 * nothing else, so a regular expression that must match at the start of a line is not thrown
 * off by prose. What each cell held is remembered, so a node found in the joined text can be
 * traced back to the cell it was written in.
 *
 * <p>Nothing here edits a notebook or pretends to be a notebook editor. smIDE has none, and a
 * {@code .ipynb} opens as the JSON it is; this is what lets the graph be drawn from one
 * anyway.
 */
final class Notebook {

    /** What a cell contributed, and where it is in the file. */
    record Cell(int index, int from, int to, int at) {

        boolean holds(int offset) {
            return offset >= from && offset < to;
        }
    }

    /**
     * A notebook's code, joined.
     *
     * @param text  the code cells, in order, as one Python text
     * @param cells what each code cell contributed to it
     */
    record Code(String text, List<Cell> cells) {

        static final Code NONE = new Code("", List.of());

        /** The cell an offset in {@link #text} came from. */
        Optional<Cell> cellAt(int offset) {
            return cells.stream().filter(cell -> cell.holds(offset)).findFirst();
        }
    }

    private Notebook() {
    }

    static boolean isNotebook(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().endsWith(".ipynb");
    }

    /**
     * Reads a notebook's code cells.
     *
     * <p>Half-written or unreadable JSON gives nothing rather than something wrong: a notebook
     * is written by a tool, so a file that does not parse is a file being saved, not a file
     * with a mistake worth reporting.
     */
    static Code code(String json) {
        JsonArray cells = cellsOf(json);
        if (cells == null) {
            return Code.NONE;
        }
        // Where each cell's source is written, in document order, to point back at later.
        List<Integer> sources = sourceKeys(json);
        boolean canPointBack = sources.size() == cells.size();
        StringBuilder text = new StringBuilder();
        List<Cell> out = new ArrayList<>();
        int index = 0;
        for (JsonElement element : cells) {
            int cellIndex = index++;
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cell = element.getAsJsonObject();
            if (!"code".equals(string(cell.get("cell_type")))) {
                continue;
            }
            String source = source(cell.get("source"));
            if (source.isBlank()) {
                continue;
            }
            int from = text.length();
            text.append(source);
            // Cells run as separate blocks; a newline between keeps the last line of one from
            // running into the first line of the next.
            if (!source.endsWith("\n")) {
                text.append('\n');
            }
            out.add(new Cell(cellIndex, from, text.length(),
                    canPointBack ? sources.get(cellIndex) : -1));
        }
        return new Code(text.toString(), List.copyOf(out));
    }

    /** The cells of a notebook, whichever format version wrote it, or null. */
    private static JsonArray cellsOf(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("cells") && root.get("cells").isJsonArray()) {
                return root.getAsJsonArray("cells");
            }
            // nbformat 3 and older kept cells inside worksheets.
            if (root.has("worksheets") && root.get("worksheets").isJsonArray()) {
                JsonArray all = new JsonArray();
                for (JsonElement sheet : root.getAsJsonArray("worksheets")) {
                    if (sheet.isJsonObject() && sheet.getAsJsonObject().has("cells")) {
                        all.addAll(sheet.getAsJsonObject().getAsJsonArray("cells"));
                    }
                }
                return all;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A cell's source: a list of lines, as notebooks are usually written, or one string. */
    private static String source(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return "";
        }
        if (source.isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement line : source.getAsJsonArray()) {
                if (line.isJsonPrimitive()) {
                    joined.append(line.getAsString());
                }
            }
            return joined.toString();
        }
        return source.isJsonPrimitive() ? source.getAsString() : "";
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Where each {@code "source"} is written, in order.
     *
     * <p>Every cell has exactly one, and the cells are written in the order they run, so the
     * nth one belongs to the nth cell. Where that does not hold - a notebook carrying
     * attachments with a source of their own - the count will not match the number of cells
     * and nothing is pointed back at, which is better than pointing at the wrong line.
     */
    private static List<Integer> sourceKeys(String json) {
        List<Integer> out = new ArrayList<>();
        int at = json.indexOf("\"source\"");
        while (at >= 0) {
            out.add(at);
            at = json.indexOf("\"source\"", at + 1);
        }
        return out;
    }
}
