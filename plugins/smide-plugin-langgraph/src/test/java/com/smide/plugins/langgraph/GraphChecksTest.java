package com.smide.plugins.langgraph;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphChecksTest {

    private static final Path FILE = Path.of("graph.py");

    private static final String WORKING = """
            from langgraph.graph import StateGraph, START, END

            builder = StateGraph(State)
            builder.add_node("plan", plan)
            builder.add_node("act", act)
            builder.add_edge(START, "plan")
            builder.add_edge("plan", "act")
            builder.add_edge("act", END)
            graph = builder.compile()
            """;

    private static List<Diagnostic> check(String python) {
        return GraphChecks.problemsIn(FILE, python);
    }

    private static List<String> messages(String python, Diagnostic.Severity severity) {
        return check(python).stream().filter(d -> d.severity() == severity).map(Diagnostic::message).toList();
    }

    @Test
    void aGraphThatWorksIsLeftAlone() {
        assertEquals(List.of(), check(WORKING));
    }

    @Test
    void aFileWithNoGraphIsNotRead() {
        assertEquals(List.of(), check("def plan(state):\n    return state\n"));
    }

    @Test
    void anEdgeToANameThatIsNotANode() {
        List<String> errors = messages(WORKING.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_edge(\"plan\", \"acr\")"), Diagnostic.Severity.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("no node called acr"), errors.get(0));
        assertTrue(errors.get(0).contains("plan, act"), errors.get(0));
    }

    @Test
    void theNameThatIsWrongIsWhatIsUnderlined() {
        String python = WORKING.replace("builder.add_edge(\"act\", END)", "builder.add_edge(\"acr\", END)");
        Diagnostic unknown = check(python).stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow();
        String line = python.split("\\R")[unknown.startLine()];
        assertEquals("acr", line.substring(unknown.startColumn(), unknown.endColumn()));
        assertEquals(Diagnostic.UNRESOLVED, unknown.code());
    }

    @Test
    void aNodeAddedTwice() {
        List<String> errors = messages(WORKING.replace("builder.add_node(\"act\", act)",
                "builder.add_node(\"plan\", act)"), Diagnostic.Severity.ERROR);
        assertTrue(errors.stream().anyMatch(m -> m.contains("already a node called plan")), errors.toString());
    }

    @Test
    void aNodeNothingLeadsTo() {
        List<String> warnings = messages(WORKING.replace("builder.add_edge(\"plan\", \"act\")", ""),
                Diagnostic.Severity.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.startsWith("Nothing leads to act")), warnings.toString());
    }

    @Test
    void aNodeThatLeadsNowhereWhileTheGraphUsesEnd() {
        String python = WORKING
                .replace("builder.add_node(\"act\", act)", "builder.add_node(\"act\", act)\nbuilder.add_node(\"log\", log)")
                .replace("builder.add_edge(\"plan\", \"act\")",
                        "builder.add_edge(\"plan\", \"act\")\nbuilder.add_edge(\"plan\", \"log\")");
        List<String> warnings = messages(python, Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("log leads nowhere"), warnings.get(0));
    }

    @Test
    void aNodeWithNoWayOnIsNotRemarkedOnWhenTheGraphNeverUsesEnd() {
        // Without END anywhere, a node with no edge out of it is how such a graph stops.
        List<String> warnings = messages(WORKING.replace("builder.add_edge(\"act\", END)", ""),
                Diagnostic.Severity.WARNING);
        assertEquals(List.of(), warnings);
    }

    @Test
    void aGraphWithNoWayIn() {
        List<String> errors = messages(WORKING.replace("builder.add_edge(START, \"plan\")", ""),
                Diagnostic.Severity.ERROR);
        assertTrue(errors.stream().anyMatch(m -> m.contains("no way in")), errors.toString());
    }

    @Test
    void anEntryPointSetRatherThanWired() {
        String python = WORKING.replace("builder.add_edge(START, \"plan\")", "builder.set_entry_point(\"plan\")");
        assertEquals(List.of(), check(python));
    }

    @Test
    void aFinishPointSetRatherThanWired() {
        String python = WORKING.replace("builder.add_edge(\"act\", END)", "builder.set_finish_point(\"act\")");
        assertEquals(List.of(), check(python));
    }

    @Test
    void aGraphThatNeverReachesEnd() {
        List<String> notes = messages(WORKING.replace("builder.add_edge(\"act\", END)", ""),
                Diagnostic.Severity.INFO);
        assertTrue(notes.stream().anyMatch(m -> m.contains("leads to END")), notes.toString());
    }

    @Test
    void conditionalEdgesAreFollowed() {
        String python = WORKING.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_conditional_edges(\"plan\", route, {\"go\": \"act\", \"stop\": END})");
        assertEquals(List.of(), check(python));
    }

    @Test
    void aConditionalEdgeToANameThatIsNotANode() {
        String python = WORKING.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_conditional_edges(\"plan\", route, {\"go\": \"acr\", \"stop\": END})");
        List<String> errors = messages(python, Diagnostic.Severity.ERROR);
        assertTrue(errors.stream().anyMatch(m -> m.contains("no node called acr")), errors.toString());
    }

    @Test
    void aMappingThatCannotBeReadSilencesReachability() {
        // route returns the next node; which ones it can return is not in the file.
        String python = WORKING.replace("builder.add_edge(\"plan\", \"act\")",
                "builder.add_conditional_edges(\"plan\", route)");
        List<String> warnings = messages(python, Diagnostic.Severity.WARNING);
        assertFalse(warnings.stream().anyMatch(m -> m.contains("Nothing leads to")), warnings.toString());
    }

    @Test
    void aNodeNamedAfterItsFunction() {
        String python = """
                from langgraph.graph import StateGraph, START, END

                builder = StateGraph(State)
                builder.add_node(plan)
                builder.add_edge(START, "plan")
                builder.add_edge("plan", END)
                """;
        assertEquals(List.of(), check(python));
    }

    @Test
    void interruptingWithoutACheckpointer() {
        String python = WORKING.replace("builder.compile()", "builder.compile(interrupt_before=[\"act\"])");
        List<String> warnings = messages(python, Diagnostic.Severity.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.startsWith("Interrupting needs a checkpointer")),
                warnings.toString());
    }

    @Test
    void interruptingWithACheckpointerIsFine() {
        String python = WORKING.replace("builder.compile()",
                "builder.compile(checkpointer=MemorySaver(), interrupt_before=[\"act\"])");
        assertEquals(List.of(), check(python));
    }

    @Test
    void commentsAndNestedBracketsInsideACall() {
        String python = """
                from langgraph.graph import StateGraph, START, END

                builder = StateGraph(State)
                builder.add_node("plan", plan)  # the first one
                builder.add_node("act", make_act(tools=[search, calculator]))
                builder.add_edge(START, "plan")
                builder.add_edge(
                    "plan",  # on to the work
                    "act",
                )
                builder.add_edge("act", END)
                """;
        assertEquals(List.of(), check(python));
    }

    @Test
    void twoGraphsInOneFileAreSeparate() {
        String python = """
                from langgraph.graph import StateGraph, START, END

                inner = StateGraph(State)
                inner.add_node("step", step)
                inner.add_edge(START, "step")
                inner.add_edge("step", END)

                outer = StateGraph(State)
                outer.add_node("sub", inner.compile())
                outer.add_edge(START, "sub")
                outer.add_edge("sub", END)
                """;
        assertEquals(List.of(), check(python));
    }
}
