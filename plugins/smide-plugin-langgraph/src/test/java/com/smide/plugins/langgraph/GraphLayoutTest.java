package com.smide.plugins.langgraph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphLayoutTest {

    private static GraphLayout.Placed place(String python) {
        return GraphLayout.of(GraphChecks.graphsIn(python).get(0));
    }

    private static final String STRAIGHT = """
            from langgraph.graph import StateGraph, START, END

            builder = StateGraph(State)
            builder.add_node("plan", plan)
            builder.add_node("act", act)
            builder.add_edge(START, "plan")
            builder.add_edge("plan", "act")
            builder.add_edge("act", END)
            """;

    @Test
    void aStraightGraphIsOneBoxPerRow() {
        GraphLayout.Placed placed = place(STRAIGHT);
        assertEquals(List.of("0:0 START", "1:0 plan", "2:0 act", "3:0 END"),
                placed.labels());
        assertEquals(4, placed.rows());
        assertEquals(1, placed.lanes());
        assertFalse(placed.unknownTargets());
    }

    @Test
    void everyEdgeIsDrawnOnce() {
        // The checks hold each edge twice over, once per end; the picture wants three lines.
        assertEquals(3, place(STRAIGHT).links().size());
    }

    @Test
    void aBranchSharesARow() {
        String python = STRAIGHT.replace("builder.add_node(\"act\", act)",
                        "builder.add_node(\"act\", act)\nbuilder.add_node(\"ask\", ask)")
                .replace("builder.add_edge(\"act\", END)",
                        "builder.add_edge(\"plan\", \"ask\")\nbuilder.add_edge(\"act\", END)\nbuilder.add_edge(\"ask\", END)");
        GraphLayout.Placed placed = place(python);
        assertEquals(2, placed.box("act").row());
        assertEquals(2, placed.box("ask").row());
        assertEquals(0, placed.box("act").lane());
        assertEquals(1, placed.box("ask").lane());
        assertEquals(2, placed.lanes());
        assertEquals(2, placed.width(2));
    }

    @Test
    void aLoopGoesBackUp() {
        String python = STRAIGHT + "builder.add_edge(\"act\", \"plan\")\n";
        GraphLayout.Link back = place(python).links().stream()
                .filter(GraphLayout.Link::back).findFirst().orElseThrow();
        assertEquals("act", back.from());
        assertEquals("plan", back.to());
        assertFalse(back.self());
    }

    @Test
    void aNodeThatCanRunAgain() {
        String python = STRAIGHT + "builder.add_edge(\"act\", \"act\")\n";
        GraphLayout.Link self = place(python).links().stream()
                .filter(GraphLayout.Link::self).findFirst().orElseThrow();
        assertEquals("act", self.from());
        assertFalse(self.back());
    }

    @Test
    void aConditionalEdgeKnowsItIsOne() {
        String python = STRAIGHT.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_conditional_edges(\"plan\", route, {\"go\": \"act\", \"stop\": END})");
        List<GraphLayout.Link> conditional = place(python).links().stream()
                .filter(GraphLayout.Link::conditional).toList();
        assertEquals(2, conditional.size(), conditional.toString());
        assertTrue(conditional.stream().allMatch(link -> link.from().equals("plan")));
    }

    @Test
    void aNodeNothingLeadsToSitsBelowTheRestAndIsMarked() {
        String python = STRAIGHT.replace("builder.add_node(\"act\", act)",
                "builder.add_node(\"act\", act)\nbuilder.add_node(\"orphan\", orphan)");
        GraphLayout.Placed placed = place(python);
        GraphLayout.Box orphan = placed.box("orphan");
        assertFalse(orphan.reachable());
        assertEquals(3, orphan.row());
        // END stays at the bottom, below what is adrift.
        assertEquals(4, placed.box(GraphChecks.END).row());
        assertTrue(placed.box("act").reachable());
    }

    @Test
    void aGraphWithNoWayInIsStillDrawn() {
        String python = STRAIGHT.replace("builder.add_edge(START, \"plan\")", "");
        GraphLayout.Placed placed = place(python);
        assertEquals(1, placed.box("plan").row());
        assertEquals(2, placed.box("act").row());
        assertTrue(placed.box("plan").reachable());
    }

    @Test
    void aMappingThatCannotBeReadIsSaidSo() {
        String python = STRAIGHT.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_conditional_edges(\"plan\", route)");
        assertTrue(place(python).unknownTargets());
    }

    @Test
    void anEdgeToANameThatIsNoNodeIsNotDrawn() {
        String python = STRAIGHT.replace("builder.add_edge(\"act\", END)", "builder.add_edge(\"act\", \"gone\")");
        GraphLayout.Placed placed = place(python);
        assertTrue(placed.links().stream().noneMatch(link -> link.to().equals("gone")));
        assertEquals(List.of("0:0 START", "1:0 plan", "2:0 act"), placed.labels());
    }

    @Test
    void aBoxKnowsWhereItsNameIsWritten() {
        GraphLayout.Placed placed = place(STRAIGHT);
        int offset = placed.box("plan").offset();
        assertEquals("plan", STRAIGHT.substring(offset, offset + 4));
    }

    @Test
    void theFunctionANodeRunsIsWhereClickingGoes() {
        String python = """
                def plan_step(state):
                    return state

                builder = StateGraph(State)
                builder.add_node("plan", plan_step)
                builder.add_edge(START, "plan")
                builder.add_edge("plan", END)
                """;
        GraphChecks.Graph graph = GraphChecks.graphsIn(python).get(0);
        assertEquals("plan_step", graph.functions.get("plan"));
        int at = Offsets.definition(python, "plan_step");
        assertEquals("plan_step", python.substring(at, at + "plan_step".length()));
    }

    @Test
    void aNodeNamedAfterItsFunctionLeadsToIt() {
        String python = """
                def plan(state):
                    return state

                builder = StateGraph(State)
                builder.add_node(plan)
                builder.add_edge(START, "plan")
                builder.add_edge("plan", END)
                """;
        assertEquals("plan", GraphChecks.graphsIn(python).get(0).functions.get("plan"));
        assertTrue(Offsets.definition(python, "plan") >= 0);
    }

    @Test
    void whatIsNotAFunctionInThisFile() {
        assertEquals(-1, Offsets.definition(STRAIGHT, "plan"));
        assertEquals(-1, Offsets.definition(STRAIGHT, null));
        assertEquals(-1, Offsets.definition("def planning():\n    pass\n", "plan"));
    }
}
