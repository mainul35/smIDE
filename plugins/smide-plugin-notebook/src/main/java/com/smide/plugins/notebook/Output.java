package com.smide.plugins.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One thing a cell produced: a line on stdout, a value, a picture, a traceback.
 *
 * <p>This wraps the output's own JSON rather than copying it into fields. A kernel sends
 * far more than anything here draws - a {@code display_data} can carry the same value as
 * plain text, HTML, LaTeX, SVG, a PNG and a vendor MIME type nobody but one library
 * understands, plus metadata saying how big the picture should be - and a notebook that
 * arrives with all of that must leave with all of that. Fields would quietly drop
 * everything they did not name, so the JSON is what is kept and the accessors are only a
 * reading of it.
 *
 * <p>An output is immutable: a cell replaces its whole list rather than editing one.
 */
public final class Output {

    /** The output types nbformat 4 defines. */
    public static final String STREAM = "stream";
    public static final String EXECUTE_RESULT = "execute_result";
    public static final String DISPLAY_DATA = "display_data";
    public static final String ERROR = "error";

    private final JsonObject raw;

    private Output(JsonObject raw) {
        this.raw = raw;
    }

    /** Wraps an output's JSON as it was read from a file or sent by a kernel. */
    public static Output of(JsonObject raw) {
        return new Output(raw.deepCopy());
    }

    /** Text a kernel wrote to {@code stdout} or {@code stderr}. */
    public static Output stream(String name, String text) {
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("output_type", STREAM);
        out.add("text", lines(text));
        return new Output(out);
    }

    /**
     * The value of the last expression in a cell.
     *
     * @param executionCount the count the kernel gave this execution, which the result
     *                       carries so that {@code Out[3]} can be labelled next to
     *                       {@code In[3]}
     */
    public static Output result(int executionCount, JsonObject data, JsonObject metadata) {
        JsonObject out = new JsonObject();
        out.add("data", asLines(data));
        out.addProperty("execution_count", executionCount);
        out.add("metadata", metadata == null ? new JsonObject() : metadata.deepCopy());
        out.addProperty("output_type", EXECUTE_RESULT);
        return new Output(out);
    }

    /** Something a cell drew or showed without it being the cell's value. */
    public static Output displayData(JsonObject data, JsonObject metadata) {
        JsonObject out = new JsonObject();
        out.add("data", asLines(data));
        out.add("metadata", metadata == null ? new JsonObject() : metadata.deepCopy());
        out.addProperty("output_type", DISPLAY_DATA);
        return new Output(out);
    }

    /** An exception that escaped the cell. */
    public static Output error(String name, String value, List<String> traceback) {
        JsonObject out = new JsonObject();
        out.addProperty("ename", name == null ? "" : name);
        out.addProperty("evalue", value == null ? "" : value);
        out.addProperty("output_type", ERROR);
        JsonArray frames = new JsonArray();
        if (traceback != null) {
            traceback.forEach(frames::add);
        }
        out.add("traceback", frames);
        return new Output(out);
    }

    /** The output's JSON, to write into a notebook. */
    JsonObject json() {
        return raw.deepCopy();
    }

    public String outputType() {
        return string(raw.get("output_type"), "");
    }

    /** {@code stdout} or {@code stderr} for a stream, empty otherwise. */
    public String streamName() {
        return string(raw.get("name"), "");
    }

    /** Whether this came from {@code stderr}, which is drawn differently. */
    public boolean isStandardError() {
        return STREAM.equals(outputType()) && "stderr".equals(streamName());
    }

    /** The count a result carries, or empty when it has none. */
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

    public String errorName() {
        return string(raw.get("ename"), "");
    }

    public String errorValue() {
        return string(raw.get("evalue"), "");
    }

    /** The traceback's frames, as the kernel split them; may contain ANSI colour codes. */
    public List<String> traceback() {
        JsonElement frames = raw.get("traceback");
        if (frames == null || !frames.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement frame : frames.getAsJsonArray()) {
            if (frame.isJsonPrimitive()) {
                out.add(frame.getAsString());
            }
        }
        return List.copyOf(out);
    }

    /**
     * This output as text, whichever shape it arrived in.
     *
     * <p>A stream's text, a result's or display's {@code text/plain}, an error's name and
     * value followed by its traceback. Empty when the output is a picture and nothing else,
     * which is the one case where there is genuinely nothing to read.
     */
    public String text() {
        return switch (outputType()) {
            case STREAM -> multiline(raw.get("text"));
            case EXECUTE_RESULT, DISPLAY_DATA -> dataText();
            case ERROR -> errorText();
            default -> "";
        };
    }

    /** A PNG this output carries, base64 as the file holds it, or empty. */
    public Optional<String> png() {
        JsonElement data = raw.get("data");
        if (data == null || !data.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement png = data.getAsJsonObject().get("image/png");
        if (png == null) {
            return Optional.empty();
        }
        String encoded = png.isJsonArray() ? multiline(png) : string(png, "");
        // A notebook written as lines of base64 has newlines in it that a decoder must not see.
        String cleaned = encoded.replaceAll("\\s", "");
        return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    private String dataText() {
        JsonElement data = raw.get("data");
        if (data == null || !data.isJsonObject()) {
            return "";
        }
        return multiline(data.getAsJsonObject().get("text/plain"));
    }

    private String errorText() {
        List<String> frames = traceback();
        if (!frames.isEmpty()) {
            // The kernel's own traceback already carries the exception line at the end.
            return String.join("\n", frames);
        }
        String name = errorName();
        String value = errorValue();
        return value.isEmpty() ? name : name + ": " + value;
    }

    /**
     * The MIME types nbformat writes as a list of lines rather than one string.
     *
     * <p>Taken from nbformat's own writer. A kernel sends each of these as one string with
     * newlines in it, and storing it that way is legal; it would also mean that every
     * output this editor records looks, to a diff, completely unlike the same output
     * recorded by Jupyter. Images are deliberately not in the list: nbformat keeps base64
     * as one string.
     */
    private static final java.util.Set<String> LINE_FORMATS = java.util.Set.of(
            "text/plain", "text/html", "text/latex", "text/markdown", "image/svg+xml",
            "application/javascript");

    /** A kernel's {@code data} with its text parts split into lines, as nbformat writes them. */
    private static JsonObject asLines(JsonObject data) {
        JsonObject out = new JsonObject();
        if (data == null) {
            return out;
        }
        for (var entry : data.entrySet()) {
            JsonElement value = entry.getValue();
            if (LINE_FORMATS.contains(entry.getKey()) && value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()) {
                out.add(entry.getKey(), lines(value.getAsString()));
            } else {
                out.add(entry.getKey(), value == null ? null : value.deepCopy());
            }
        }
        return out;
    }

    /**
     * A value nbformat allows to be either one string or a list of lines, read as one string.
     *
     * <p>The list form keeps the newline on the end of each line, so joining with nothing is
     * right and joining with {@code \n} would double them.
     */
    static String multiline(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement line : element.getAsJsonArray()) {
                if (line.isJsonPrimitive()) {
                    joined.append(line.getAsString());
                }
            }
            return joined.toString();
        }
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    /**
     * Text as the list of lines nbformat prefers, each keeping its newline.
     *
     * <p>Writing one long string with {@code \n} in it would be legal and would make every
     * notebook this touches a one-line diff against the same notebook saved by Jupyter. The
     * list form is what every other tool writes, so it is what this writes.
     */
    static JsonArray lines(String text) {
        JsonArray out = new JsonArray();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(from, i + 1));
                from = i + 1;
            }
        }
        if (from < text.length()) {
            out.add(text.substring(from));
        }
        return out;
    }

    private static String string(JsonElement element, String fallback) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    @Override
    public String toString() {
        return outputType() + (STREAM.equals(outputType()) ? "(" + streamName() + ")" : "");
    }
}
