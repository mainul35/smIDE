package com.smide.vcs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which lines of a file have changed since it was last committed, and how.
 *
 * <p>Answered for the text in the editor rather than the text on disk, so a line is marked as it
 * is typed. What comes back is one mark per line of the file as it stands now: added, changed, or
 * "something was removed here" - the three things IntelliJ draws in the strip beside the code.
 *
 * <p>The comparison is a patience diff: the lines that appear exactly once in both versions are
 * matched up first, and the stretches between those anchors are compared the same way again. It
 * is what Git itself uses for a reason - a block moved or repeated, which trips up a plainer
 * comparison into marking half the file, is matched by the lines around it that are unmistakable.
 */
public final class LineChanges {

    /** What happened to a line. */
    public enum Kind {
        /** This line is new since the last commit. */
        ADDED,
        /** This line existed and has been changed. */
        CHANGED,
        /** Lines were removed just above this one. */
        REMOVED
    }

    /**
     * One stretch of a file that differs from the commit, and what it replaced.
     *
     * @param start     first line of the stretch as the file stands now
     * @param end       one past its last line; equal to {@code start} when lines were only removed
     * @param baseStart first line of what was there in the commit
     * @param baseEnd   one past its last line
     */
    public record Hunk(Kind kind, int start, int end, int baseStart, int baseEnd) {

        public boolean covers(int line) {
            return kind == Kind.REMOVED ? line == start : line >= start && line < end;
        }

        /** The lines this replaced, as they were committed. */
        public List<String> committedLines(String committed) {
            List<String> lines = lines(committed);
            return baseStart >= baseEnd || baseStart >= lines.size()
                    ? List.of()
                    : List.copyOf(lines.subList(baseStart, Math.min(baseEnd, lines.size())));
        }
    }

    /** Beyond this many lines the comparison is not worth the wait; nothing is marked. */
    private static final int MAX_LINES = 200_000;

    private LineChanges() {
    }

    /**
     * The marks for {@code current}, given what was committed.
     *
     * @return line number (from zero) to what happened there; lines that have not changed are
     *         absent
     */
    public static Map<Integer, Kind> between(String committed, String current) {
        Map<Integer, Kind> marks = new HashMap<>();
        for (Hunk hunk : hunks(committed, current)) {
            switch (hunk.kind()) {
                case REMOVED -> marks.putIfAbsent(hunk.start(), Kind.REMOVED);
                case ADDED -> {
                    for (int line = hunk.start(); line < hunk.end(); line++) {
                        marks.put(line, Kind.ADDED);
                    }
                }
                case CHANGED -> {
                    int replaced = hunk.baseEnd() - hunk.baseStart();
                    for (int line = hunk.start(); line < hunk.end(); line++) {
                        marks.put(line, line - hunk.start() < replaced ? Kind.CHANGED : Kind.ADDED);
                    }
                    if (replaced > hunk.end() - hunk.start()) {
                        // More lines went than came; the last of what replaced them says so.
                        marks.putIfAbsent(Math.max(hunk.end() - 1, hunk.start()), Kind.REMOVED);
                    }
                }
            }
        }
        return marks;
    }

    /** Every stretch that differs from the commit, in the order they appear in the file. */
    public static List<Hunk> hunks(String committed, String current) {
        if (committed == null || current == null) {
            return List.of();
        }
        List<String> was = lines(committed);
        List<String> now = lines(current);
        if (was.size() > MAX_LINES || now.size() > MAX_LINES) {
            return List.of();
        }
        List<Hunk> hunks = new ArrayList<>();
        compare(was, 0, was.size(), now, 0, now.size(), hunks);
        hunks.sort(java.util.Comparator.comparingInt(Hunk::start));
        return List.copyOf(hunks);
    }

    /** Splits into lines, keeping every one - including the empty last line of a file that ends in a newline. */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /**
     * Compares one stretch of each version, and marks what differs.
     *
     * <p>{@code [wasFrom, wasTo)} against {@code [nowFrom, nowTo)}, ends excluded.
     */
    private static void compare(List<String> was, int wasFrom, int wasTo,
                                List<String> now, int nowFrom, int nowTo,
                                List<Hunk> hunks) {
        // The identical ends of the two stretches are not worth comparing.
        while (wasFrom < wasTo && nowFrom < nowTo && was.get(wasFrom).equals(now.get(nowFrom))) {
            wasFrom++;
            nowFrom++;
        }
        while (wasTo > wasFrom && nowTo > nowFrom && was.get(wasTo - 1).equals(now.get(nowTo - 1))) {
            wasTo--;
            nowTo--;
        }
        if (wasFrom == wasTo && nowFrom == nowTo) {
            return;
        }
        if (wasFrom == wasTo) {
            // Nothing was there before: every line here is new.
            hunks.add(new Hunk(Kind.ADDED, nowFrom, nowTo, wasFrom, wasFrom));
            return;
        }
        if (nowFrom == nowTo) {
            // Everything that was there is gone; the mark goes on the line that took its place.
            hunks.add(new Hunk(Kind.REMOVED, Math.max(Math.min(nowFrom, now.size() - 1), 0),
                    Math.max(Math.min(nowFrom, now.size() - 1), 0), wasFrom, wasTo));
            return;
        }
        List<int[]> anchors = anchors(was, wasFrom, wasTo, now, nowFrom, nowTo);
        if (anchors.isEmpty()) {
            hunks.add(new Hunk(Kind.CHANGED, nowFrom, nowTo, wasFrom, wasTo));
            return;
        }
        int wasAt = wasFrom;
        int nowAt = nowFrom;
        for (int[] anchor : anchors) {
            compare(was, wasAt, anchor[0], now, nowAt, anchor[1], hunks);
            wasAt = anchor[0] + 1;
            nowAt = anchor[1] + 1;
        }
        compare(was, wasAt, wasTo, now, nowAt, nowTo, hunks);
    }

    /**
     * The lines that appear exactly once in both stretches, matched in the order they appear in
     * both - which is the longest such run, so a block that moved does not drag the matching
     * with it.
     */
    private static List<int[]> anchors(List<String> was, int wasFrom, int wasTo,
                                       List<String> now, int nowFrom, int nowTo) {
        Map<String, Integer> onceInWas = countedOnce(was, wasFrom, wasTo);
        Map<String, Integer> onceInNow = countedOnce(now, nowFrom, nowTo);
        List<int[]> pairs = new ArrayList<>();
        for (int line = nowFrom; line < nowTo; line++) {
            Integer there = onceInWas.get(now.get(line));
            Integer here = onceInNow.get(now.get(line));
            if (there != null && here != null && here == line) {
                pairs.add(new int[] {there, line});
            }
        }
        return longestRun(pairs);
    }

    /** The lines of a stretch that appear in it exactly once, and where. */
    private static Map<String, Integer> countedOnce(List<String> lines, int from, int to) {
        Map<String, Integer> where = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (int line = from; line < to; line++) {
            String text = lines.get(line);
            count.merge(text, 1, Integer::sum);
            where.put(text, line);
        }
        where.keySet().removeIf(text -> count.get(text) != 1);
        return where;
    }

    /**
     * The longest run of pairs whose positions rise on both sides - the matches that can all be
     * true at once, because one cannot be above another in one version and below it in the other.
     */
    private static List<int[]> longestRun(List<int[]> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }
        int[] best = new int[pairs.size()];
        int[] previous = new int[pairs.size()];
        int bestLength = 0;
        int bestEnd = 0;
        for (int i = 0; i < pairs.size(); i++) {
            best[i] = 1;
            previous[i] = -1;
            for (int j = 0; j < i; j++) {
                if (pairs.get(j)[0] < pairs.get(i)[0] && best[j] + 1 > best[i]) {
                    best[i] = best[j] + 1;
                    previous[i] = j;
                }
            }
            if (best[i] > bestLength) {
                bestLength = best[i];
                bestEnd = i;
            }
        }
        List<int[]> run = new ArrayList<>();
        for (int at = bestEnd; at >= 0; at = previous[at]) {
            run.add(pairs.get(at));
        }
        java.util.Collections.reverse(run);
        return run;
    }
}
