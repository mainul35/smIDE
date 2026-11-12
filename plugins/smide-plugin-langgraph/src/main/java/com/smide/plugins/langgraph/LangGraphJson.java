package com.smide.plugins.langgraph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smide.api.problems.Diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code langgraph.json}, read and checked where it is written.
 *
 * <p>The file is the one thing a LangGraph deployment cannot do without, and every mistake in
 * it - a graph pointing at a file that has been renamed, an attribute that is no longer there,
 * a dependency folder that does not exist - is found today by running the CLI and reading what
 * it says. They are all in the file, so they are said in the file.
 */
public final class LangGraphJson {

    /** What these problems are reported under. */
    public static final String SOURCE = "langgraph";

    private LangGraphJson() {
    }

    public static boolean isConfig(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().equals(LangGraph.CONFIG);
    }

    /** The graphs a configuration names, in the order it names them. */
    public static Map<String, LangGraph.GraphSpec> graphs(String text) {
        Map<String, LangGraph.GraphSpec> out = new LinkedHashMap<>();
        JsonObject root = object(text).orElse(null);
        if (root == null || !root.has("graphs") || !root.get("graphs").isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("graphs").entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            LangGraph.parseSpec(entry.getKey(), entry.getValue().getAsString())
                    .ifPresent(spec -> out.put(spec.id(), spec));
        }
        return out;
    }

    /** The graphs of the configuration beside a project root, or none when it has none. */
    public static Map<String, LangGraph.GraphSpec> graphsOf(Path root) {
        return graphs(LangGraph.read(root.resolve(LangGraph.CONFIG)));
    }

    static Optional<JsonObject> object(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed != null && parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static List<Diagnostic> problemsIn(Path file, String text) {
        if (text.isBlank()) {
            return List.of();
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            return List.of();
        }
        JsonObject root = object(text).orElse(null);
        if (root == null) {
            // Half-typed JSON is for the JSON language server to complain about, not for this.
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        checkGraphs(file, text, dir, root, out);
        checkDependencies(file, text, dir, root, out);
        checkEnv(file, text, dir, root, out);
        return out;
    }

    private static void checkGraphs(Path file, String text, Path dir, JsonObject root, List<Diagnostic> out) {
        if (!root.has("graphs") || !root.get("graphs").isJsonObject()
                || root.getAsJsonObject("graphs").size() == 0) {
            out.add(at(file, text, 0, 1, Diagnostic.Severity.ERROR,
                    "No graphs: a deployment serves what \"graphs\" names, such as"
                            + " \"graphs\": {\"agent\": \"./src/agent/graph.py:graph\"}.", null));
            return;
        }
        int graphsAt = text.indexOf("\"graphs\"");
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("graphs").entrySet()) {
            String id = entry.getKey();
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String written = entry.getValue().getAsString();
            int[] span = valueOf(text, graphsAt, id).orElse(null);
            int start = span == null ? 0 : span[0];
            int end = span == null ? 1 : span[1];
            Optional<LangGraph.GraphSpec> parsed = LangGraph.parseSpec(id, written);
            if (parsed.isEmpty()) {
                out.add(at(file, text, start, end, Diagnostic.Severity.ERROR,
                        "A graph is written as a file and what is in it, separated by a colon:"
                                + " \"./src/agent/graph.py:graph\".", null));
                continue;
            }
            LangGraph.GraphSpec spec = parsed.get();
            if (!spec.isFile()) {
                // A dotted module is resolved against the deployment's path, which is not this folder.
                continue;
            }
            Path target = spec.file(dir);
            if (!Files.isRegularFile(target)) {
                out.add(at(file, text, start, end, Diagnostic.Severity.ERROR,
                        "There is no " + spec.module() + " here: the graph \"" + id
                                + "\" points at a file that does not exist.", Diagnostic.UNRESOLVED));
                continue;
            }
            if (!defines(LangGraph.read(target), spec.attribute())) {
                out.add(at(file, text, start, end, Diagnostic.Severity.WARNING,
                        spec.module() + " defines nothing called " + spec.attribute()
                                + ", so the graph \"" + id + "\" cannot be loaded from it.", Diagnostic.UNRESOLVED));
            }
        }
    }

    private static void checkDependencies(Path file, String text, Path dir, JsonObject root, List<Diagnostic> out) {
        if (!root.has("dependencies") || !root.get("dependencies").isJsonArray()) {
            out.add(at(file, text, 0, 1, Diagnostic.Severity.ERROR,
                    "No dependencies: a deployment installs what \"dependencies\" names, usually"
                            + " [\".\"] for the project itself.", null));
            return;
        }
        int from = text.indexOf("\"dependencies\"");
        int search = from < 0 ? 0 : from;
        for (JsonElement element : root.getAsJsonArray("dependencies")) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String written = element.getAsString();
            int[] span = literal(text, search, written).orElse(null);
            if (span != null) {
                search = span[1];
            }
            // Only a local path can be checked here; a package name is PyPI's business.
            if (!written.equals(".") && !written.startsWith("./") && !written.startsWith("../")) {
                continue;
            }
            Path target = dir.resolve(written).normalize();
            if (!Files.exists(target)) {
                out.add(at(file, text, span == null ? 0 : span[0], span == null ? 1 : span[1],
                        Diagnostic.Severity.ERROR, "There is no " + written + " here.", Diagnostic.UNRESOLVED));
            }
        }
    }

    private static void checkEnv(Path file, String text, Path dir, JsonObject root, List<Diagnostic> out) {
        if (!root.has("env") || !root.get("env").isJsonPrimitive()) {
            return;
        }
        String written = root.get("env").getAsString();
        int from = text.indexOf("\"env\"");
        int[] span = literal(text, from < 0 ? 0 : from, written).orElse(null);
        Path target = dir.resolve(written).normalize();
        if (!Files.exists(target)) {
            out.add(at(file, text, span == null ? 0 : span[0], span == null ? 1 : span[1],
                    Diagnostic.Severity.WARNING, "There is no " + written + " here, so the deployment"
                            + " starts with none of the variables it names.", Diagnostic.UNRESOLVED));
        }
    }

    /** Whether a Python file assigns, defines or imports a name at its top level. */
    static boolean defines(String python, String name) {
        if (python.isBlank()) {
            return false;
        }
        String n = Pattern.quote(name);
        Pattern pattern = Pattern.compile("(?m)^(?:async\\s+def|def|class)\\s+" + n + "\\b"
                + "|^" + n + "\\s*(?::[^=\\r\\n]+)?=(?!=)"
                + "|^\\s*(?:from|import)\\b.*\\b" + n + "\\b");
        return pattern.matcher(python).find();
    }

    /** Where the string value of {@code "key"} after {@code from} is written, without its quotes. */
    static Optional<int[]> valueOf(String text, int from, String key) {
        int at = text.indexOf('"' + key + '"', Math.max(from, 0));
        if (at < 0) {
            return Optional.empty();
        }
        Matcher value = Pattern.compile("\"((?:[^\"\\\\\\r\\n]|\\\\.)*)\"")
                .matcher(text).region(at + key.length() + 2, text.length());
        return value.find() ? Optional.of(new int[] {value.start(1), value.end(1)}) : Optional.empty();
    }

    /** Where a particular string value is written, after {@code from}, without its quotes. */
    static Optional<int[]> literal(String text, int from, String value) {
        int at = text.indexOf('"' + value + '"', Math.max(from, 0));
        return at < 0 ? Optional.empty() : Optional.of(new int[] {at + 1, at + 1 + value.length()});
    }

    static Diagnostic at(Path file, String text, int start, int end, Diagnostic.Severity severity,
                         String message, String code) {
        int[] from = Text.lineColumn(text, start);
        int[] to = Text.lineColumn(text, Math.max(end, start + 1));
        return new Diagnostic(file, from[0], from[1], to[0], to[1], severity, message, SOURCE, code);
    }
}
