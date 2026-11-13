package com.smide.plugins.notebook;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * A Jupyter notebook: its cells, its metadata, and the JSON it came from.
 *
 * <p>The thing this class exists to get right is the round trip. A notebook is a file
 * several programs edit - Jupyter Lab, VS Code, Colab, nbconvert, a script in CI that
 * strips outputs - and each of them writes keys the others do not read. An editor that
 * parsed a notebook into typed fields and wrote those fields back would be correct by the
 * schema and still ruinous in practice: the first save would drop the collaborator's
 * kernel pin, the widget state, the cell ids Colab uses to track comments, and the diff
 * would be the whole file. So the parsed JSON is what is held, every edit is made on it in
 * place, and writing is serialising the same object. Keys nothing here understands are
 * neither read nor touched, and survive because they were never taken out.
 *
 * <p>The cell list is the exception, because order is the one thing an editor really does
 * change. {@link #cells()} is the order the cells are in; the JSON arrays they live in are
 * rebuilt from it when the notebook is written. There can be more than one such array: an
 * nbformat 3 notebook keeps its cells inside {@code worksheets}, a list of lists that the
 * format allowed and no tool ever used for more than one entry. Each cell remembers which
 * array it came from, so a notebook with two worksheets is written back with two
 * worksheets, with each cell in the one it started in.
 *
 * <p>No JavaFX here, and nothing about editors: this is the file, and it is tested as the
 * file.
 */
public final class Notebook {

    /** What nbformat 4.5 wants a cell id to look like: short, and unique in the file. */
    private static final int ID_LENGTH = 8;
    private static final char[] ID_ALPHABET = "0123456789abcdef".toCharArray();

    /**
     * Written the way nbformat's own writer writes: one space of indent, no HTML escaping.
     *
     * <p>Both matter only for the diff. Gson's pretty printing indents by two and escapes
     * {@code <}, {@code >} and {@code &} as {@code <} and friends, which is valid JSON
     * and valid nbformat, and would still mean that every notebook saved here looked
     * entirely rewritten to anyone who opened it next in Jupyter.
     */
    private static final Gson GSON = new GsonBuilder()
            .setFormattingStyle(FormattingStyle.PRETTY.withIndent(" "))
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final JsonObject root;
    private final List<Cell> cells;
    /** The array a cell with nowhere else to go is written into. */
    private final JsonArray primary;
    private final Random ids = new Random();

    private Notebook(JsonObject root, List<Cell> cells, JsonArray primary) {
        this.root = root;
        this.cells = cells;
        this.primary = primary;
    }

    /**
     * Reads a notebook.
     *
     * @throws NotebookFormatException when the text is not JSON, is not an object, or is an
     *                                 object with no cells anywhere in it
     */
    public static Notebook read(String json) throws NotebookFormatException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new NotebookFormatException(brokenAt(e.getMessage()), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            // Typing straight into a new .ipynb lands here, and so does a file some other
            // tool wrote as a list. Saying what was found beats "invalid notebook".
            throw new NotebookFormatException("the file is not JSON at all");
        }
        JsonObject root = parsed.getAsJsonObject();

        if (root.has("cells") && root.get("cells").isJsonArray()) {
            JsonArray array = root.getAsJsonArray("cells");
            return new Notebook(root, read(array), array);
        }
        // nbformat 3 and older kept the cells inside worksheets.
        if (root.has("worksheets") && root.get("worksheets").isJsonArray()) {
            List<Cell> cells = new ArrayList<>();
            JsonArray primary = null;
            for (JsonElement sheet : root.getAsJsonArray("worksheets")) {
                if (!sheet.isJsonObject()) {
                    continue;
                }
                JsonElement sheetCells = sheet.getAsJsonObject().get("cells");
                if (sheetCells == null || !sheetCells.isJsonArray()) {
                    continue;
                }
                JsonArray array = sheetCells.getAsJsonArray();
                if (primary == null) {
                    primary = array;
                }
                cells.addAll(read(array));
            }
            if (primary == null) {
                throw new NotebookFormatException("it has worksheets, but no cells in any of them");
            }
            return new Notebook(root, cells, primary);
        }
        throw new NotebookFormatException("it is JSON, but has no \"cells\"");
    }

    /** A notebook with nothing in it, for the language given, ready to be saved. */
    public static Notebook empty(String language) {
        JsonObject root = new JsonObject();
        JsonArray cells = new JsonArray();
        root.add("cells", cells);
        JsonObject metadata = new JsonObject();
        JsonObject kernelspec = new JsonObject();
        kernelspec.addProperty("display_name", "Python 3");
        kernelspec.addProperty("language", language == null ? "python" : language);
        kernelspec.addProperty("name", "python3");
        metadata.add("kernelspec", kernelspec);
        root.add("metadata", metadata);
        root.addProperty("nbformat", 4);
        root.addProperty("nbformat_minor", 5);
        Notebook notebook = new Notebook(root, new ArrayList<>(), cells);
        notebook.insert(0, CellKind.CODE, "");
        return notebook;
    }

    /**
     * Where the JSON broke, said to the person whose file it is.
     *
     * <p>The parser's own message is no use to them. It names the exception class, it ends
     * with a JSONPath, and - worst - it advises turning on a lenient mode, which is advice
     * to a programmer calling a library, not to somebody looking at their own file in an
     * editor. All that is really wanted out of it is the position, which is the one thing
     * that helps: it says where to look.
     */
    static String brokenAt(String parserMessage) {
        String broken = "the JSON is broken or unfinished";
        if (parserMessage == null) {
            return broken;
        }
        java.util.regex.Matcher where = java.util.regex.Pattern
                .compile("at line (\\d+) column (\\d+)").matcher(parserMessage);
        return where.find()
                ? broken + ", at line " + where.group(1) + ", column " + where.group(2)
                : broken;
    }

    private static List<Cell> read(JsonArray array) {
        List<Cell> cells = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                cells.add(new Cell(element.getAsJsonObject(), array));
            }
        }
        return cells;
    }

    /** The cells, in the order they run. The list is a view; use the edits below. */
    public List<Cell> cells() {
        return List.copyOf(cells);
    }

    public int cellCount() {
        return cells.size();
    }

    public Cell cell(int index) {
        return cells.get(index);
    }

    public int indexOf(Cell cell) {
        return cells.indexOf(cell);
    }

    /** The notebook's own metadata object, which is edited in place. */
    public JsonObject metadata() {
        JsonElement metadata = root.get("metadata");
        if (metadata == null || !metadata.isJsonObject()) {
            JsonObject fresh = new JsonObject();
            root.add("metadata", fresh);
            return fresh;
        }
        return metadata.getAsJsonObject();
    }

    public int nbformat() {
        JsonElement version = root.get("nbformat");
        try {
            return version != null && version.isJsonPrimitive() ? version.getAsInt() : 4;
        } catch (RuntimeException e) {
            return 4;
        }
    }

    public int nbformatMinor() {
        JsonElement minor = root.get("nbformat_minor");
        try {
            return minor != null && minor.isJsonPrimitive() ? minor.getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The language the notebook says its code is in, lower-case, or empty.
     *
     * <p>Read from {@code kernelspec.language} and then from {@code language_info.name},
     * which is where a notebook that has actually been run keeps it. A notebook that says
     * nothing is treated as Python by the editor, because that is what a notebook in a
     * project with a Python interpreter is.
     */
    public Optional<String> language() {
        JsonObject metadata = metadata();
        for (String[] path : new String[][]{{"kernelspec", "language"}, {"language_info", "name"}}) {
            JsonElement holder = metadata.get(path[0]);
            if (holder != null && holder.isJsonObject()) {
                JsonElement name = holder.getAsJsonObject().get(path[1]);
                if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()) {
                    return Optional.of(name.getAsString().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether this notebook's code is Python, which is all this plugin can run. */
    public boolean isPython() {
        return language().map(language -> language.startsWith("python")).orElse(true);
    }

    // ---------------------------------------------------------------- edits

    /** Adds an empty cell at an index, pushing the rest down, and returns it. */
    public Cell insert(int index, CellKind kind, String source) {
        int at = Math.max(0, Math.min(index, cells.size()));
        // A new cell joins whichever array its neighbour is in, so a worksheet stays whole.
        JsonArray owner = primary;
        if (!cells.isEmpty()) {
            owner = cells.get(Math.min(at, cells.size() - 1)).owner();
        }
        Cell cell = Cell.create(kind, source == null ? "" : source, newId(), owner);
        cells.add(at, cell);
        return cell;
    }

    public Cell delete(int index) {
        return cells.remove(index);
    }

    /**
     * Moves a cell to another index.
     *
     * <p>The target is where the cell ends up in the finished list, not where it was before
     * the cell was taken out: moving cell 0 to 2 in a notebook of four leaves it third,
     * which is what a reader who pressed Move Down twice expects.
     */
    public void move(int from, int to) {
        if (from == to || from < 0 || from >= cells.size()) {
            return;
        }
        int target = Math.max(0, Math.min(to, cells.size() - 1));
        Cell cell = cells.remove(from);
        cells.add(target, cell);
    }

    /**
     * Splits a cell in two at a character offset, and returns the new, lower one.
     *
     * <p>The lower half keeps the cell's kind and metadata but none of its outputs or its
     * execution count: whatever ran, ran against the whole cell, so claiming either half
     * produced it would be a lie. The upper half loses its outputs for the same reason.
     */
    public Cell split(int index, int offset) {
        Cell cell = cells.get(index);
        String source = cell.source();
        int at = Math.max(0, Math.min(offset, source.length()));
        Cell lower = cell.copyWith(source.substring(at), newId());
        cell.setSource(source.substring(0, at));
        cell.clearOutputs();
        cell.setExecutionCount(null);
        cells.add(index + 1, lower);
        return lower;
    }

    /**
     * Joins a cell with the one below it, and returns the surviving cell.
     *
     * <p>The merged cell keeps the upper one's kind, because that is the cell the reader is
     * standing in. A newline is put between the two halves where the upper one did not end
     * in one, so that two blocks of Python do not become one broken line.
     */
    public Cell mergeWithNext(int index) {
        if (index < 0 || index + 1 >= cells.size()) {
            return cells.get(Math.max(0, Math.min(index, cells.size() - 1)));
        }
        Cell upper = cells.get(index);
        Cell lower = cells.get(index + 1);
        String first = upper.source();
        String second = lower.source();
        String joined = first.isEmpty() || first.endsWith("\n") ? first + second : first + "\n" + second;
        upper.setSource(joined);
        upper.clearOutputs();
        upper.setExecutionCount(null);
        cells.remove(index + 1);
        return upper;
    }

    /** Clears every cell's outputs and execution count, as Restart and Clear does. */
    public void clearAllOutputs() {
        for (Cell cell : cells) {
            cell.clearOutputs();
            cell.setExecutionCount(null);
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * The notebook as JSON, ready to be written to the file.
     *
     * <p>Ends in a newline, as every other notebook writer does, so that a save does not
     * show up as a change to the last line.
     */
    public String toJson() {
        rebuildCellArrays();
        return GSON.toJson(root) + "\n";
    }

    /**
     * Puts the cells back into the arrays they belong to, in the order they are now in.
     *
     * <p>Each array is emptied and refilled from the cell list, which is what makes an
     * insert, a delete and a move show up in the file. Cells whose array has gone - which
     * cannot happen through the edits here, but could if a notebook were read, emptied and
     * refilled - fall back to the primary array rather than disappearing.
     */
    private void rebuildCellArrays() {
        Set<JsonArray> arrays = new LinkedHashSet<>();
        arrays.add(primary);
        for (Cell cell : cells) {
            arrays.add(cell.owner());
        }
        for (JsonArray array : arrays) {
            while (!array.isEmpty()) {
                array.remove(0);
            }
        }
        for (Cell cell : cells) {
            JsonArray owner = cell.owner();
            if (owner == null) {
                owner = primary;
                cell.setOwner(owner);
            }
            owner.add(cell.json());
        }
    }

    /**
     * An id for a new cell, or null where the notebook is too old to carry one.
     *
     * <p>nbformat added cell ids in 4.5 and made them required there; writing one into a
     * 4.4 notebook makes that notebook fail validation in tools that check, so a notebook
     * that has no ids keeps having none.
     */
    private String newId() {
        if (nbformat() < 4 || (nbformat() == 4 && nbformatMinor() < 5)) {
            return null;
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                id.append(ID_ALPHABET[ids.nextInt(ID_ALPHABET.length)]);
            }
            String candidate = id.toString();
            if (cells.stream().noneMatch(cell -> cell.id().filter(candidate::equals).isPresent())) {
                return candidate;
            }
        }
        return null;
    }
}
