package com.smide.plugins.notebook;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Putting pyright's answers back on the cells they belong to.
 *
 * <p>Tested without a pyright: what is being checked is arithmetic on line numbers, which
 * is where the idea of showing a checker the concatenation can go wrong, and the process
 * has nothing to do with it.
 */
class PyrightMappingTest {

    private static Notebook notebook() throws Exception {
        return Notebook.read("""
                {"cells":[
                 {"cell_type":"markdown","metadata":{},"source":["# Title"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["import json\\n"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["a = 1\\n","b = nope\\n"]}],
                 "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);
    }

    private static Diagnostic at(int line, int fromColumn, int toColumn, String message) {
        Diagnostic diagnostic = new Diagnostic(
                new Range(new Position(line, fromColumn), new Position(line, toColumn)), message);
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        return diagnostic;
    }

    @Test
    void aProblemLandsOnTheCellAndLineItCameFrom() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        // Joined text is "import json\na = 1\nb = nope\n": the problem is on line 2.
        List<Pyright.CellDiagnostics> spread = Pyright.spread(context,
                List.of(at(2, 4, 8, "\"nope\" is not defined")));

        Pyright.CellDiagnostics cell = spread.stream()
                .filter(each -> each.cellIndex() == 2).findFirst().orElseThrow();
        assertEquals(1, cell.diagnostics().size());
        com.smide.api.problems.Diagnostic problem = cell.diagnostics().get(0);
        assertEquals(1, problem.startLine(), "second line of the third cell");
        assertEquals(4, problem.startColumn());
        assertEquals(8, problem.endColumn());
        assertEquals("pyright", problem.source());
        assertEquals(com.smide.api.problems.Diagnostic.Severity.ERROR, problem.severity());
        assertEquals("\"nope\" is not defined", problem.message());
    }

    @Test
    void aProblemInAnEarlierCellStaysInThatCell() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        List<Pyright.CellDiagnostics> spread = Pyright.spread(context,
                List.of(at(0, 7, 11, "\"json\" is unused")));

        Pyright.CellDiagnostics cell = spread.stream()
                .filter(each -> each.cellIndex() == 1).findFirst().orElseThrow();
        assertEquals(0, cell.diagnostics().get(0).startLine());
    }

    /** Every cell in the context is named, so a cell that is now clean is told to clear. */
    @Test
    void aCellWithNoProblemsIsStillReportedSoItsUnderlinesGoAway() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        List<Pyright.CellDiagnostics> spread = Pyright.spread(context,
                List.of(at(2, 4, 8, "\"nope\" is not defined")));

        Pyright.CellDiagnostics clean = spread.stream()
                .filter(each -> each.cellIndex() == 1).findFirst().orElseThrow();
        assertTrue(clean.diagnostics().isEmpty());
    }

    @Test
    void theProseCellIsNeverMentioned() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        List<Pyright.CellDiagnostics> spread = Pyright.spread(context, List.of());

        assertTrue(spread.stream().noneMatch(each -> each.cellIndex() == 0));
        assertEquals(2, spread.size());
    }

    @Test
    void aProblemPastTheEndOfEveryCellIsDropped() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        List<Pyright.CellDiagnostics> spread = Pyright.spread(context,
                List.of(at(99, 0, 1, "nowhere")));

        assertTrue(spread.stream().allMatch(each -> each.diagnostics().isEmpty()));
    }

    @Test
    void aProblemThatSpansTwoCellsIsClippedToTheOneItStartsIn() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        Diagnostic spanning = new Diagnostic(
                new Range(new Position(0, 0), new Position(2, 3)), "unterminated");
        spanning.setSeverity(DiagnosticSeverity.Error);
        List<Pyright.CellDiagnostics> spread = Pyright.spread(context, List.of(spanning));

        com.smide.api.problems.Diagnostic problem = spread.stream()
                .filter(each -> each.cellIndex() == 1).findFirst().orElseThrow()
                .diagnostics().get(0);
        assertEquals(0, problem.startLine());
        assertEquals(0, problem.endLine(), "clipped, not pointing into the next cell");
    }

    @Test
    void aSeverityPyrightDoesNotGiveIsAWarningRatherThanNothing() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        Diagnostic plain = new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 1)), "hmm");
        List<Pyright.CellDiagnostics> spread = Pyright.spread(context, List.of(plain));

        assertEquals(com.smide.api.problems.Diagnostic.Severity.WARNING, spread.stream()
                .filter(each -> each.cellIndex() == 1).findFirst().orElseThrow()
                .diagnostics().get(0).severity());
    }

    @Test
    void nothingToSpreadIsNoProblem() {
        assertTrue(Pyright.spread(null, List.of()).isEmpty());
    }
}
