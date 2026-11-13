package com.smide.plugins.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One cell of a notebook: what is written in it, and what came back when it was run.
 *
 * <p>Like {@link Output}, a cell is its own JSON with accessors over it rather than a set
 * of fields. The reason is the same and the stakes are higher: a cell carries whatever the
 * tool that wrote it decided to put there - VS Code's language id, Colab's provenance,
 * Deepnote's cell ids, a {@code collapsed} flag from a Jupyter five years old, {@code
 * attachments} holding an image pasted into a markdown cell - and all of it is somebody's
 * state that smIDE has no business discarding. Reading the known keys and writing back
 * through the same object means the unknown ones are still there afterwards.
 *
 * <p>Whether the cell's source was written as one string or as a list of lines is
 * remembered and kept, so a save does not reshape a file that was fine.
 */
public final class Cell {

    private final JsonObject raw;
    /** The array in the notebook's JSON this cell belongs to; see {@link Notebook}. */
    private JsonArray owner;
    /** Whether {@code source} arrived as one string rather than a list of lines. */
    private boolean sourceWasString;

    Cell(JsonObject raw, JsonArray owner) {
        this.raw = raw;
        this.owner = owner;
        JsonElement source = raw.get("source");
        this.sourceWasString = source != null && source.isJsonPrimitive();
    }

    /**
     * A new, empty cell.
     *
     * @param id the {@code id} nbformat 4.5 requires, or null for a notebook too old to
     *           have the field - writing one into a 4.4 notebook makes it fail validation
     */
    static Cell create(CellKind kind, String source, String id, JsonArray owner) {
        JsonObject raw = new JsonObject();
        // Written in the order nbformat's own writer uses, so a new cell reads like an old one.
        raw.addProperty("cell_type", kind.jsonName());
        if (id != null) {
            raw.addProperty("id", id);
        }
        raw.add("metadata", new JsonObject());
        if (kind == CellKind.CODE) {
            raw.add("execution_count", com.google.gson.JsonNull.INSTANCE);
            raw.add("outputs", new JsonArray());
        }
        raw.add("source", Output.lines(source));
        return new Cell(raw, owner);
    }

    JsonObject json() {
        return raw;
    }

    JsonArray owner() {
        return owner;
    }

    void setOwner(JsonArray owner) {
        this.owner = owner;
    }

    public CellKind kind() {
        JsonElement type = raw.get("cell_type");
        return CellKind.parse(type != null && type.isJsonPrimitive() ? type.getAsString() : null);
    }

    /**
     * Changes what kind of cell this is, adding and removing the fields that go with it.
     *
     * <p>The nbformat schema is strict about this: a code cell must have {@code outputs}
     * and {@code execution_count}, and a markdown or raw cell must have neither. A cell
     * turned from code into markdown therefore loses its outputs, which is also what a
     * reader expects - prose has no output - and is why this is the one edit that throws
     * something away.
     */
    public void setKind(CellKind kind) {
        if (kind == kind()) {
            return;
        }
        raw.addProperty("cell_type", kind.jsonName());
        if (kind == CellKind.CODE) {
            if (!raw.has("outputs")) {
                raw.add("outputs", new JsonArray());
            }
            if (!raw.has("execution_count")) {
                raw.add("execution_count", com.google.gson.JsonNull.INSTANCE);
            }
        } else {
            raw.remove("outputs");
            raw.remove("execution_count");
        }
    }

    /** The cell's {@code id}, where the notebook is new enough to have them. */
    public Optional<String> id() {
        JsonElement id = raw.get("id");
        return id != null && id.isJsonPrimitive() ? Optional.of(id.getAsString()) : Optional.empty();
    }

    public String source() {
        return Output.multiline(raw.get("source"));
    }

    /** Replaces what is written in the cell, in whichever shape the cell already used. */
    public void setSource(String source) {
        if (sourceWasString) {
            raw.addProperty("source", source == null ? "" : source);
        } else {
            raw.add("source", Output.lines(source));
        }
    }

    /** How many times the kernel had run anything when it ran this, or empty for never. */
    public Optional<Integer> executionCount() {
        JsonElement count = raw.get("execution_count");
        if (count == null || count.isJsonNull() || !count.isJsonPrimitive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(count.getAsInt());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Sets the count, or clears it with null; only a code cell has one. */
    public void setExecutionCount(Integer count) {
        if (kind() != CellKind.CODE) {
            return;
        }
        if (count == null) {
            raw.add("execution_count", com.google.gson.JsonNull.INSTANCE);
        } else {
            raw.addProperty("execution_count", count);
        }
    }

    public List<Output> outputs() {
        JsonElement outputs = raw.get("outputs");
        if (outputs == null || !outputs.isJsonArray()) {
            return List.of();
        }
        List<Output> out = new ArrayList<>();
        for (JsonElement element : outputs.getAsJsonArray()) {
            if (element.isJsonObject()) {
                out.add(Output.of(element.getAsJsonObject()));
            }
        }
        return List.copyOf(out);
    }

    public void setOutputs(List<Output> outputs) {
        if (kind() != CellKind.CODE) {
            return;
        }
        JsonArray array = new JsonArray();
        if (outputs != null) {
            outputs.forEach(output -> array.add(output.json()));
        }
        raw.add("outputs", array);
    }

    public void addOutput(Output output) {
        if (kind() != CellKind.CODE || output == null) {
            return;
        }
        JsonElement existing = raw.get("outputs");
        JsonArray array = existing != null && existing.isJsonArray()
                ? existing.getAsJsonArray() : new JsonArray();
        array.add(output.json());
        raw.add("outputs", array);
    }

    public void clearOutputs() {
        if (kind() == CellKind.CODE) {
            raw.add("outputs", new JsonArray());
        }
    }

    /** A copy of this cell, for a split or a duplicate; the copy carries no outputs. */
    Cell copyWith(String source, String id) {
        JsonObject copy = raw.deepCopy();
        if (id == null) {
            copy.remove("id");
        } else {
            copy.addProperty("id", id);
        }
        Cell cell = new Cell(copy, owner);
        cell.sourceWasString = sourceWasString;
        cell.setSource(source);
        cell.clearOutputs();
        cell.setExecutionCount(null);
        return cell;
    }

    @Override
    public String toString() {
        String source = source();
        String first = source.lines().findFirst().orElse("");
        return kind().jsonName() + ": " + (first.length() > 40 ? first.substring(0, 40) : first);
    }
}
