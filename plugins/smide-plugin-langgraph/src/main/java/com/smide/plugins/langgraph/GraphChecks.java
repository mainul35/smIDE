package com.smide.plugins.langgraph;

import com.smide.api.problems.Diagnostic;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is wrong with a graph before anything is run.
 *
 * <p>A graph is built by calls - a node added here, an edge added forty lines down - and the
 * mistakes they make are the mistakes of a drawing done blind: an edge to a node whose name
 * was changed, a node nothing leads to, two nodes with the same name, no way in. Every one of
 * them is found today by running the graph and reading an exception, or worse, by watching a
 * run quietly do nothing. They are all visible in the file, so they are said in the file, as
 * the Problems window and red under the name that is wrong.
 *
 * <p>What is read is the calls, not Python: a name that comes from a variable or a loop cannot
 * be known here and is passed over rather than guessed at. Nothing is reported unless it is
 * certain, because an IDE that cries wolf about a working graph is worse than one that says
 * nothing.
 */
public final class GraphChecks {

    /** What these problems are reported under. */
    public static final String SOURCE = "langgraph-graph";

    /** The names LangGraph gives the two ends of a graph. */
    static final String START = "__start__";
    static final String END = "__end__";

    private static final Pattern BUILDER =
            Pattern.compile("(\\w+)\\s*=\\s*(?:StateGraph|MessageGraph|Graph)\\s*\\(");
    private static final Pattern CALL = Pattern.compile("(\\w+)\\s*\\.\\s*(add_node|add_edge|add_conditional_edges"
            + "|set_entry_point|set_finish_point|set_conditional_entry_point|compile)\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\r\\n]*)\"|'([^'\\r\\n]*)'");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w.]*");
    /** A dict entry's value: {@code "continue": "tools"} or {@code "stop": END}. */
    private static final Pattern MAPPED = Pattern.compile(":\\s*(?:\"([^\"\\r\\n]*)\"|'([^'\\r\\n]*)'|(\\w+))");

    private GraphChecks() {
    }

    /** A Python file in a LangGraph project - those are the only ones worth reading for graphs. */
    public static boolean isGraphFile(Path file) {
        return file != null && ProjectFiles.hasExtension(file, "py") && LangGraph.rootOf(file).isPresent();
    }

    public static List<Diagnostic> problemsIn(Path file, String text) {
        // Cheap first: most files in a project build no graph.
        if (!text.contains("StateGraph") && !text.contains("MessageGraph")) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        for (Graph graph : graphsIn(text)) {
            check(file, text, graph, out);
        }
        return out;
    }

    /** One node, where its name is written. */
    record Node(String name, int start, int end) {
    }

    /** One edge, and where the name of the end that can be wrong is written. */
    record Edge(String from, String to, int start, int end) {
    }

    /** A graph built in a file: the variable it is built on, and what was added to it. */
    static final class Graph {
        final String variable;
        final int at;
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        /** Nodes something leads away from, even where where it leads cannot be read. */
        final Set<String> leadsAway = new LinkedHashSet<>();
        /** Whether some edge's target could not be read - a mapping built at runtime, a list. */
        boolean unknownTargets;
        /** A compile() that asks to interrupt, and where it is written, when no checkpointer is given. */
        int interruptWithoutCheckpointer = -1;

        Graph(String variable, int at) {
            this.variable = variable;
            this.at = at;
        }
    }

    /** The graphs built in a file, in the order they are built. */
    static List<Graph> graphsIn(String text) {
        Map<String, Graph> byVariable = new LinkedHashMap<>();
        Matcher builder = BUILDER.matcher(text);
        while (builder.find()) {
            // A variable built on twice is one graph as far as its names go: the later one wins.
            byVariable.put(builder.group(1), new Graph(builder.group(1), builder.start(1)));
        }
        if (byVariable.isEmpty()) {
            return List.of();
        }
        Matcher call = CALL.matcher(text);
        while (call.find()) {
            Graph graph = byVariable.get(call.group(1));
            if (graph == null) {
                continue;
            }
            int open = call.end() - 1;
            int close = closing(text, open);
            if (close < 0) {
                continue;
            }
            List<int[]> args = arguments(text, open + 1, close);
            switch (call.group(2)) {
                case "add_node" -> addNode(text, args, graph);
                case "add_edge" -> addEdge(text, args, graph);
                case "add_conditional_edges" -> addConditional(text, args, graph, 0);
                case "set_entry_point" -> endpoint(text, args, 0).ifPresent(
                        to -> graph.edges.add(new Edge(START, to.name(), to.start(), to.end())));
                case "set_finish_point" -> endpoint(text, args, 0).ifPresent(from -> {
                    graph.edges.add(new Edge(from.name(), END, from.start(), from.end()));
                    graph.leadsAway.add(from.name());
                });
                case "set_conditional_entry_point" -> {
                    // No source: the mapping's targets are reached from START.
                    for (Node to : mapped(text, args, 1)) {
                        graph.edges.add(new Edge(START, to.name(), to.start(), to.end()));
                    }
                }
                case "compile" -> {
                    String written = text.substring(open + 1, close);
                    if ((written.contains("interrupt_before") || written.contains("interrupt_after"))
                            && !written.contains("checkpointer")) {
                        graph.interruptWithoutCheckpointer = call.start(2);
                    }
                }
                default -> {
                }
            }
        }
        return List.copyOf(byVariable.values());
    }

    private static void addNode(String text, List<int[]> args, Graph graph) {
        if (args.isEmpty()) {
            return;
        }
        int[] first = args.get(0);
        Optional<Node> quoted = literalIn(text, first);
        if (quoted.isPresent()) {
            graph.nodes.add(quoted.get());
            return;
        }
        // add_node(my_node): the node is named after the function, module prefix dropped.
        identifierIn(text, first).ifPresent(written -> {
            String full = written.name();
            String simple = full.substring(full.lastIndexOf('.') + 1);
            int at = written.end() - simple.length();
            graph.nodes.add(new Node(simple, at, written.end()));
        });
    }

    private static void addEdge(String text, List<int[]> args, Graph graph) {
        Optional<Node> from = endpoint(text, args, 0);
        Optional<Node> to = endpoint(text, args, 1);
        if (from.isEmpty()) {
            return;
        }
        graph.leadsAway.add(from.get().name());
        if (to.isEmpty()) {
            graph.unknownTargets = true;
            return;
        }
        // Once reported on the target, which is the end usually wrong, and once on the source.
        graph.edges.add(new Edge(from.get().name(), to.get().name(), to.get().start(), to.get().end()));
        graph.edges.add(new Edge(from.get().name(), to.get().name(), from.get().start(), from.get().end()));
    }

    private static void addConditional(String text, List<int[]> args, Graph graph, int source) {
        Optional<Node> from = endpoint(text, args, source);
        if (from.isEmpty()) {
            return;
        }
        // Something leads away from here whether or not the mapping can be read.
        graph.leadsAway.add(from.get().name());
        List<Node> targets = mapped(text, args, source + 2);
        if (targets.isEmpty()) {
            graph.unknownTargets = true;
        }
        for (Node to : targets) {
            graph.edges.add(new Edge(from.get().name(), to.name(), to.start(), to.end()));
        }
        // The source itself is worth checking even when the mapping cannot be read.
        graph.edges.add(new Edge(from.get().name(), from.get().name(), from.get().start(), from.get().end()));
    }

    /** The quoted name written as an argument, where a quoted name is all there is. */
    private static Optional<Node> literalIn(String text, int[] span) {
        String raw = text.substring(span[0], span[1]);
        Matcher literal = LITERAL.matcher(raw);
        if (!literal.find() || !raw.substring(0, literal.start()).isBlank()) {
            return Optional.empty();
        }
        String name = literal.group(1) != null ? literal.group(1) : literal.group(2);
        int at = span[0] + literal.start() + 1;
        return Optional.of(new Node(name, at, at + name.length()));
    }

    /** The name written as an argument, where the argument is a name and nothing else. */
    private static Optional<Node> identifierIn(String text, int[] span) {
        String raw = text.substring(span[0], span[1]);
        Matcher identifier = IDENTIFIER.matcher(raw);
        if (!identifier.find() || !raw.substring(0, identifier.start()).isBlank()
                || !raw.substring(identifier.end()).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Node(identifier.group(), span[0] + identifier.start(), span[0] + identifier.end()));
    }

    /** The targets of a mapping argument: {@code {"continue": "tools", "stop": END}}. */
    private static List<Node> mapped(String text, List<int[]> args, int index) {
        if (index >= args.size()) {
            return List.of();
        }
        int[] span = args.get(index);
        String written = text.substring(span[0], span[1]).strip();
        if (!written.startsWith("{")) {
            // A list of node names, or a callable's return type: nothing certain to read.
            return List.of();
        }
        List<Node> out = new ArrayList<>();
        Matcher entry = MAPPED.matcher(text).region(span[0], span[1]);
        while (entry.find()) {
            for (int group = 1; group <= 2; group++) {
                if (entry.group(group) != null) {
                    out.add(new Node(entry.group(group), entry.start(group), entry.end(group)));
                }
            }
            if (entry.group(3) != null) {
                constant(entry.group(3)).ifPresent(name ->
                        out.add(new Node(name, entry.start(3), entry.end(3))));
            }
        }
        return out;
    }

    /** One end of an edge: a name in quotes, or START or END. */
    private static Optional<Node> endpoint(String text, List<int[]> args, int index) {
        if (index >= args.size()) {
            return Optional.empty();
        }
        int[] span = args.get(index);
        Optional<Node> quoted = literalIn(text, span);
        if (quoted.isPresent()) {
            return quoted;
        }
        // START and END are names, not strings; any other name is a variable and unknowable.
        return identifierIn(text, span)
                .flatMap(written -> constant(written.name())
                        .map(name -> new Node(name, written.start(), written.end())));
    }

    /** START and END, however they are imported; any other name is a variable and unknowable. */
    private static Optional<String> constant(String identifier) {
        String simple = identifier.substring(identifier.lastIndexOf('.') + 1);
        return switch (simple) {
            case "START", "__start__" -> Optional.of(START);
            case "END", "__end__" -> Optional.of(END);
            default -> Optional.empty();
        };
    }

    private static void check(Path file, String text, Graph graph, List<Diagnostic> out) {
        Set<String> names = new LinkedHashSet<>();
        for (Node node : graph.nodes) {
            if (!names.add(node.name())) {
                out.add(at(file, text, node.start(), node.end(), Diagnostic.Severity.ERROR,
                        "There is already a node called " + node.name()
                                + " in this graph. Adding it twice is an error when the graph is compiled.", null));
            }
        }
        if (names.isEmpty()) {
            return;
        }
        for (Edge edge : graph.edges) {
            String written = text.substring(edge.start(), edge.end());
            String name = written.equals("START") || written.equals("END") ? null : written;
            if (name == null || names.contains(name) || name.equals(START) || name.equals(END)) {
                continue;
            }
            out.add(at(file, text, edge.start(), edge.end(), Diagnostic.Severity.ERROR,
                    "This graph has no node called " + name + ". Its nodes are "
                            + String.join(", ", names) + ".", Diagnostic.UNRESOLVED));
        }
        boolean fromStart = graph.edges.stream().anyMatch(e -> e.from().equals(START));
        if (!fromStart) {
            out.add(at(file, text, graph.at, graph.at + graph.variable.length(), Diagnostic.Severity.ERROR,
                    "This graph has no way in: add an edge from START, or set_entry_point."
                            + " Compiling it without one is an error.", null));
        }
        boolean toEnd = graph.edges.stream().anyMatch(e -> e.to().equals(END));
        if (!toEnd) {
            out.add(at(file, text, graph.at, graph.at + graph.variable.length(), Diagnostic.Severity.INFO,
                    "Nothing in this graph leads to END. It stops when a node has nowhere to go,"
                            + " which is easy to mistake for a missing edge.", null));
        }
        Set<String> reached = reachable(graph, names);
        for (Node node : graph.nodes) {
            // Where some target could not be read, a node may be reached by one of those: say nothing.
            if (!graph.unknownTargets && !reached.contains(node.name())) {
                out.add(at(file, text, node.start(), node.end(), Diagnostic.Severity.WARNING,
                        "Nothing leads to " + node.name() + ": it is in the graph and no edge reaches it,"
                                + " so it never runs.", null));
            } else if (toEnd && !graph.leadsAway.contains(node.name())) {
                out.add(at(file, text, node.start(), node.end(), Diagnostic.Severity.WARNING,
                        node.name() + " leads nowhere: the graph uses END elsewhere, so an edge"
                                + " from here is probably missing.", null));
            }
        }
        if (graph.interruptWithoutCheckpointer >= 0) {
            int at = graph.interruptWithoutCheckpointer;
            out.add(at(file, text, at, at + "compile".length(), Diagnostic.Severity.WARNING,
                    "Interrupting needs a checkpointer: without one the graph cannot be resumed"
                            + " where it stopped. Pass checkpointer= to compile().", null));
        }
    }

    /** The nodes an edge reaches from START. */
    private static Set<String> reachable(Graph graph, Set<String> names) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Edge edge : graph.edges) {
            out.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
        }
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(START));
        while (!todo.isEmpty()) {
            String node = todo.removeFirst();
            for (String next : out.getOrDefault(node, List.of())) {
                if (names.contains(next) && seen.add(next)) {
                    todo.addLast(next);
                }
            }
        }
        return seen;
    }

    /** The offset of the bracket that closes the one at {@code open}, or -1. */
    static int closing(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> {
                    if (--depth == 0) {
                        return i;
                    }
                }
                case '#' -> {
                    // A comment inside a call: skip to the end of the line.
                    int line = text.indexOf('\n', i);
                    i = line < 0 ? text.length() : line;
                }
                default -> {
                }
            }
        }
        return -1;
    }

    /** Where each argument of a call is written, commas inside brackets and strings not counted. */
    static List<int[]> arguments(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        out.add(new int[] {start, i});
                        start = i + 1;
                    }
                }
                case '#' -> {
                    int line = text.indexOf('\n', i);
                    i = line < 0 ? to : line;
                }
                default -> {
                }
            }
        }
        if (start < to && !text.substring(start, to).isBlank()) {
            out.add(new int[] {start, to});
        }
        return out;
    }

    private static Diagnostic at(Path file, String text, int start, int end, Diagnostic.Severity severity,
                                 String message, String code) {
        int[] from = Text.lineColumn(text, start);
        int[] until = Text.lineColumn(text, Math.max(end, start + 1));
        return new Diagnostic(file, from[0], from[1], until[0], until[1], severity, message, SOURCE, code);
    }
}
