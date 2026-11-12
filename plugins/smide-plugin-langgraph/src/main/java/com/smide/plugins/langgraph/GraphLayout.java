package com.smide.plugins.langgraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each node of a graph is drawn: a row per step away from START, a lane within the row.
 *
 * <p>A graph is read downwards, because that is how the code that builds it reads and how a run
 * of it is told: START at the top, then what runs first, then what runs next. Nodes an edge
 * reaches in the same number of steps share a row, which is what makes a branch look like a
 * branch. An edge that goes back up - a node returning to the one that called it, which is what
 * an agent loop is - is drawn as a back edge rather than being forced into the ordering, because
 * a loop is not a mistake in the drawing and should not look like one.
 *
 * <p>No JavaFX here: a layout is arithmetic, and arithmetic can be tested.
 */
final class GraphLayout {

    /** One node, placed. */
    record Box(String name, int row, int lane, Kind kind, boolean reachable, int offset) {

        /** What to write in it. */
        String label() {
            return switch (kind) {
                case START -> "START";
                case END -> "END";
                default -> name;
            };
        }
    }

    enum Kind { START, END, NODE }

    /**
     * One edge, placed.
     *
     * @param back whether it goes to a row at or above its own - a loop, or a retry
     */
    record Link(String from, String to, boolean conditional, boolean back, boolean self) {
    }

    /** A placed graph: the boxes, the links, and how many rows and lanes it needs. */
    record Placed(List<Box> boxes, List<Link> links, int rows, int lanes, boolean unknownTargets) {

        Box box(String name) {
            return boxes.stream().filter(b -> b.name().equals(name)).findFirst().orElse(null);
        }

        boolean isEmpty() {
            return boxes.isEmpty();
        }

        /** How many boxes are in a row, for centring it. */
        int width(int row) {
            return (int) boxes.stream().filter(b -> b.row() == row).count();
        }
    }

    private GraphLayout() {
    }

    static Placed of(GraphChecks.Graph graph) {
        List<GraphChecks.Edge> edges = graph.drawn();
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (GraphChecks.Node node : graph.nodes) {
            nodes.add(node.name());
            offsets.putIfAbsent(node.name(), node.start());
        }
        if (nodes.isEmpty()) {
            return new Placed(List.of(), List.of(), 0, 0, graph.unknownTargets);
        }
        boolean hasStart = edges.stream().anyMatch(e -> e.from().equals(GraphChecks.START));
        boolean hasEnd = edges.stream().anyMatch(e -> e.to().equals(GraphChecks.END));

        Map<String, Integer> rows = rows(edges, nodes, hasStart);
        int last = rows.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // Whatever no edge reaches goes below the rest, where it can be seen to be adrift.
        List<String> adrift = nodes.stream().filter(name -> !rows.containsKey(name)).toList();
        for (String name : adrift) {
            rows.put(name, last + 1);
        }
        if (hasEnd) {
            // Below everything, including whatever is adrift, so it reads as the bottom of the run.
            rows.put(GraphChecks.END, (adrift.isEmpty() ? last : last + 1) + 1);
        }
        if (hasStart) {
            rows.put(GraphChecks.START, 0);
        }

        List<Box> boxes = new ArrayList<>();
        Map<Integer, Integer> lanes = new HashMap<>();
        List<String> order = new ArrayList<>(rows.keySet());
        // Drawn in row order, and within a row in the order the file adds them.
        order.sort(Comparator.comparingInt(rows::get)
                .thenComparingInt(name -> offsets.getOrDefault(name, Integer.MAX_VALUE)));
        for (String name : order) {
            int row = rows.get(name);
            int lane = lanes.merge(row, 1, Integer::sum) - 1;
            Kind kind = name.equals(GraphChecks.START) ? Kind.START
                    : name.equals(GraphChecks.END) ? Kind.END : Kind.NODE;
            boxes.add(new Box(name, row, lane, kind, !adrift.contains(name),
                    offsets.getOrDefault(name, -1)));
        }

        List<Link> links = new ArrayList<>();
        for (GraphChecks.Edge edge : edges) {
            Integer from = rows.get(edge.from());
            Integer to = rows.get(edge.to());
            if (from == null || to == null) {
                // An edge naming something that is no node: the problem is reported, not drawn.
                continue;
            }
            links.add(new Link(edge.from(), edge.to(), edge.conditional(),
                    to <= from && !edge.from().equals(edge.to()), edge.from().equals(edge.to())));
        }
        int rowCount = rows.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int laneCount = lanes.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        return new Placed(List.copyOf(boxes), List.copyOf(links), rowCount, laneCount, graph.unknownTargets);
    }

    /** How many steps from START each node is, by the shortest way there. */
    private static Map<String, Integer> rows(List<GraphChecks.Edge> edges, Set<String> nodes, boolean hasStart) {
        Map<String, List<String>> onwards = new LinkedHashMap<>();
        for (GraphChecks.Edge edge : edges) {
            onwards.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
        }
        Map<String, Integer> rows = new LinkedHashMap<>();
        Deque<String> todo = new ArrayDeque<>();
        if (hasStart) {
            todo.add(GraphChecks.START);
            rows.put(GraphChecks.START, 0);
        } else {
            // No way in: the first node added is as good a place to start reading as any.
            String first = nodes.iterator().next();
            todo.add(first);
            rows.put(first, 1);
        }
        while (!todo.isEmpty()) {
            String at = todo.removeFirst();
            for (String next : onwards.getOrDefault(at, List.of())) {
                if (!nodes.contains(next) || rows.containsKey(next)) {
                    continue;
                }
                rows.put(next, rows.get(at) + 1);
                todo.addLast(next);
            }
        }
        rows.remove(GraphChecks.START);
        return rows;
    }
}
