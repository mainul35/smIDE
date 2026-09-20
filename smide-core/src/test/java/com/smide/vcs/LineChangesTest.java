package com.smide.vcs;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which lines are marked, and as what, against the last commit. */
class LineChangesTest {

    private static final String COMMITTED = """
            package shop;

            class Order {
                int total() {
                    return 1;
                }
            }
            """;

    @Test
    void anUntouchedFileIsNotMarked() {
        assertEquals(Map.of(), LineChanges.between(COMMITTED, COMMITTED));
    }

    @Test
    void aChangedLineIsChangedAndOnlyThatLine() {
        String now = COMMITTED.replace("return 1;", "return 2;");

        Map<Integer, LineChanges.Kind> marks = LineChanges.between(COMMITTED, now);

        assertEquals(Map.of(4, LineChanges.Kind.CHANGED), marks);
    }

    /** The reader's own case: three lines indented, nothing else touched. */
    @Test
    void reindentedLinesAreChanged() {
        String now = COMMITTED.replace("        return 1;", "            return 1;");

        assertEquals(Map.of(4, LineChanges.Kind.CHANGED), LineChanges.between(COMMITTED, now));
    }

    @Test
    void newLinesAreAdded() {
        String now = COMMITTED.replace("    int total() {", "    int count;\n\n    int total() {");

        Map<Integer, LineChanges.Kind> marks = LineChanges.between(COMMITTED, now);

        assertEquals(Map.of(3, LineChanges.Kind.ADDED, 4, LineChanges.Kind.ADDED), marks);
    }

    @Test
    void removedLinesAreMarkedWhereTheyWere() {
        String now = COMMITTED.replace("    int total() {\n        return 1;\n    }\n", "");

        Map<Integer, LineChanges.Kind> marks = LineChanges.between(COMMITTED, now);

        assertEquals(Map.of(3, LineChanges.Kind.REMOVED), marks);
    }

    @Test
    void twoEditsFarApartAreTwoMarksAndNotOneBlock() {
        StringBuilder committed = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            committed.append("line ").append(i).append('\n');
        }
        String now = committed.toString().replace("line 10\n", "line ten\n").replace("line 90\n", "line ninety\n");

        Map<Integer, LineChanges.Kind> marks = LineChanges.between(committed.toString(), now);

        assertEquals(Map.of(10, LineChanges.Kind.CHANGED, 90, LineChanges.Kind.CHANGED), marks);
    }

    @Test
    void aFileWithNoCommittedVersionIsAllNew() {
        Map<Integer, LineChanges.Kind> marks = LineChanges.between("", "one\ntwo\n");

        assertEquals(Map.of(0, LineChanges.Kind.ADDED, 1, LineChanges.Kind.ADDED), marks);
    }

    @Test
    void aChangeKnowsWhatItReplaced() {
        String now = COMMITTED.replace("return 1;", "return 2;");

        java.util.List<LineChanges.Hunk> hunks = LineChanges.hunks(COMMITTED, now);

        assertEquals(1, hunks.size(), () -> "hunks: " + hunks);
        LineChanges.Hunk hunk = hunks.get(0);
        assertEquals(LineChanges.Kind.CHANGED, hunk.kind());
        assertEquals(4, hunk.start());
        assertEquals(5, hunk.end());
        assertTrue(hunk.covers(4));
        assertEquals(java.util.List.of("        return 1;"), hunk.committedLines(COMMITTED));
    }

    @Test
    void whatWasRemovedIsKeptWithTheChange() {
        String now = COMMITTED.replace("    int total() {\n        return 1;\n    }\n", "");

        java.util.List<LineChanges.Hunk> hunks = LineChanges.hunks(COMMITTED, now);

        assertEquals(1, hunks.size(), () -> "hunks: " + hunks);
        assertEquals(LineChanges.Kind.REMOVED, hunks.get(0).kind());
        assertEquals(java.util.List.of("    int total() {", "        return 1;", "    }"),
                hunks.get(0).committedLines(COMMITTED));
    }

    @Test
    void twoEditsAreTwoChanges() {
        StringBuilder committed = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            committed.append("line ").append(i).append('\n');
        }
        String now = committed.toString()
                .replace("line 5\n", "line five\n")
                .replace("line 30\n", "line thirty\n");

        assertEquals(2, LineChanges.hunks(committed.toString(), now).size());
    }

    @Test
    void aBlockThatMovedDoesNotMarkEverythingBetween() {
        String committed = "a\nb\nc\nd\ne\nf\ng\nh\n";
        String now = "a\nc\nd\ne\nf\ng\nb\nh\n";

        Map<Integer, LineChanges.Kind> marks = LineChanges.between(committed, now);

        assertTrue(marks.size() <= 3, () -> "marked too much: " + marks);
    }
}
