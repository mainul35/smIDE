package com.smide.plugins.notebook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a cell is Python the interpreter would accept, asked of the interpreter itself.
 *
 * <p>Python has no compile step, so there is no such thing as a compile error to show before
 * running. The nearest true thing is what {@code compile()} says, and that is what this asks:
 * the project's own interpreter, the one that will run the cell, parsing the cell's text and
 * reporting where it gives up. Nothing is executed - {@code compile} builds code and does not
 * run it - so a cell that would delete your home directory is as safe to check as any other.
 *
 * <p>This exists beside pyright rather than behind it. pyright is a download, needs Node, and
 * may be absent, starting or broken; the interpreter is already there, because without one
 * the notebook cannot run at all. So the most basic thing a reader wants - a red mark under
 * the bracket they forgot to close - should not depend on the most elaborate part of the
 * stack. When both are present they agree, and pyright adds everything else.
 *
 * <p>Checking is one process per cell. That is cheap next to starting a kernel and is only
 * done after typing has settled, but it is the reason this does not run on every keystroke.
 */
final class SyntaxCheck {

    /**
     * Asks the interpreter to parse, and prints where it failed.
     *
     * <p>Reads the source from standard input rather than the command line: a cell can hold
     * anything, including quotes, newlines and characters a command line would eat.
     */
    private static final String PROGRAM = """
            import sys
            source = sys.stdin.read()
            try:
                compile(source, "cell", "exec")
            except SyntaxError as e:
                print("%d\\t%d\\t%s" % (e.lineno or 1, e.offset or 1, e.msg), end="")
            except ValueError as e:
                print("1\\t1\\t%s" % e, end="")
            """;

    /**
     * The program as one argument that no shell can damage.
     *
     * <p>{@link #PROGRAM} cannot be passed to {@code -c} directly. Windows gives a process a
     * single command string rather than a list, so the runtime has to quote the arguments
     * back together, and a program with newlines and double quotes in it does not survive
     * that - it arrives truncated or mangled, and the interpreter then parses nothing and
     * reports nothing, which looks exactly like a cell with no mistakes in it. That is how
     * this was found: the program worked perfectly from a shell and silently found nothing
     * from Java.
     *
     * <p>Base64 has none of the characters that quoting cares about, so the argument arrives
     * as it left whatever the platform does with it.
     */
    static String argument() {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(PROGRAM.getBytes(StandardCharsets.UTF_8));
        return "import base64;exec(base64.b64decode('" + encoded + "'))";
    }

    private SyntaxCheck() {
    }

    /**
     * Where a cell stops being Python.
     *
     * @param line   one-based, as Python counts
     * @param column one-based
     */
    record Problem(int line, int column, String message) {
    }

    private static final Pattern REPORTED = Pattern.compile("^(\\d+)\\t(\\d+)\\t(.*)$", Pattern.DOTALL);

    /** What the checking program printed, as a problem; empty when it printed nothing. */
    static Optional<Problem> parse(String said) {
        if (said == null || said.isBlank()) {
            return Optional.empty();
        }
        Matcher found = REPORTED.matcher(said.strip());
        if (!found.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Problem(Math.max(1, Integer.parseInt(found.group(1))),
                    Math.max(1, Integer.parseInt(found.group(2))), found.group(3).strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a cell is worth asking about at all.
     *
     * <p>An empty cell, and a cell of nothing but magics or shell lines, are not Python and
     * would be reported as broken by anything that tried to parse them. They are perfectly
     * ordinary in a notebook, so they are left alone rather than underlined.
     */
    static boolean worthChecking(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        return source.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .anyMatch(line -> !line.startsWith("%") && !line.startsWith("!"));
    }

    /** Asks the interpreter. Off the UI thread: it starts a process. */
    static Optional<Problem> check(Path interpreter, String source) {
        if (interpreter == null || !worthChecking(source)) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(interpreter.toString(), "-I", "-c", argument())
                    .start();
            try (var in = process.getOutputStream()) {
                in.write(source.getBytes(StandardCharsets.UTF_8));
            }
            String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            // A non-zero exit is the interpreter failing to start, not the cell being wrong;
            // the cell being wrong is printed, and exits 0.
            return process.exitValue() == 0 ? parse(said) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
