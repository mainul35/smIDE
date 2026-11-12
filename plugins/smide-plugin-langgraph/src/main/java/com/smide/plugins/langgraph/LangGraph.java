package com.smide.plugins.langgraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a LangGraph project is, and what its {@code langgraph.json} says a graph is.
 *
 * <p>A project is one that has a {@code langgraph.json}, or whose own manifest asks for
 * {@code langgraph}: a graph written before the configuration file exists is still a graph,
 * and an IDE that said nothing until the deployment file was written would be no use while
 * the first graph is being written.
 */
public final class LangGraph {

    /** The CLI's configuration file, which names the graphs a deployment serves. */
    public static final String CONFIG = "langgraph.json";

    /** How far up from a file its project root is looked for. */
    private static final int MAX_UP = 6;

    /** Whether a folder is a project root, remembered: asked for every Python file, on a timer. */
    private static final Map<Path, Boolean> ROOTS = new ConcurrentHashMap<>();

    private LangGraph() {
    }

    /** Whether a project at this root is a LangGraph one. */
    public static boolean isProject(Path root) {
        if (root == null) {
            return false;
        }
        if (Files.isRegularFile(root.resolve(CONFIG))) {
            return true;
        }
        return asksForLangGraph(read(root.resolve("pyproject.toml")))
                || asksForLangGraph(read(root.resolve("requirements.txt")));
    }

    /** The same, remembered for the length of a session - a root does not stop being one. */
    static boolean isProjectCached(Path root) {
        Boolean known = ROOTS.get(root);
        if (known != null && known) {
            return true;
        }
        boolean is = isProject(root);
        // Only a yes is kept: a project that has just had its langgraph.json written becomes one.
        if (is) {
            ROOTS.put(root, true);
        }
        return is;
    }

    /** The project root above a file, if the file is in a LangGraph project at all. */
    public static Optional<Path> rootOf(Path file) {
        Path at = file == null ? null : file.toAbsolutePath().getParent();
        for (int up = 0; at != null && up < MAX_UP; up++, at = at.getParent()) {
            if (isProjectCached(at)) {
                return Optional.of(at);
            }
        }
        return Optional.empty();
    }

    /** A manifest that names langgraph as a dependency - not langgraph-sdk or langgraph-cli alone. */
    static boolean asksForLangGraph(String manifest) {
        for (String line : manifest.split("\\R")) {
            String text = line.strip();
            int hash = text.indexOf('#');
            if (hash == 0) {
                continue;
            }
            if (hash > 0) {
                text = text.substring(0, hash).strip();
            }
            for (String name : names(text)) {
                if (name.equals("langgraph") || name.equals("langgraph-api")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The package names written on one line of a manifest, lowercased, without versions or extras. */
    private static List<String> names(String line) {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z][A-Za-z0-9._\\-]*)\\s*(?:\\[[^]]*])?\\s*(?:[=<>!~]=?|@)?")
                .matcher(line);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
        return out;
    }

    /**
     * One graph as langgraph.json names it: {@code "agent": "./src/agent/graph.py:graph"}.
     *
     * @param id     the name the deployment serves it under
     * @param module the file it is in, relative to the configuration, or a dotted module name
     * @param attribute what in that file the graph is: a compiled graph, a builder, or a function returning one
     */
    public record GraphSpec(String id, String module, String attribute) {

        /** Whether the graph is named by a file rather than by an importable module. */
        public boolean isFile() {
            return module.endsWith(".py");
        }

        /** The file it is in, under the folder langgraph.json is in. */
        public Path file(Path configDir) {
            return configDir.resolve(module).normalize();
        }
    }

    /**
     * Reads {@code "./src/agent/graph.py:graph"}.
     *
     * <p>A Windows path is not what this holds - the CLI's own separator is {@code /} - but a
     * drive letter would make {@code :} ambiguous, so the last colon is the one that splits.
     */
    public static Optional<GraphSpec> parseSpec(String id, String spec) {
        if (id == null || id.isBlank() || spec == null) {
            return Optional.empty();
        }
        int colon = spec.lastIndexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) {
            return Optional.empty();
        }
        String module = spec.substring(0, colon).strip();
        String attribute = spec.substring(colon + 1).strip();
        if (module.isBlank() || attribute.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GraphSpec(id, module, attribute));
    }

    static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
