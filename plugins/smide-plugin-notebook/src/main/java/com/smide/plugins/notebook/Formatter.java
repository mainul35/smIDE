package com.smide.plugins.notebook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Tidying a cell's Python, with ruff.
 *
 * <p>ruff rather than black for two reasons that both matter in a notebook. It is one
 * download of a few megabytes instead of a dependency tree, which is the difference between
 * installing it quietly when first asked and interrupting somebody to explain why their
 * environment is about to grow; and it formats from standard input to standard output, so a
 * cell can be formatted as the fragment it is, without a file on disk for something that
 * exists only inside a JSON document.
 *
 * <p>A cell is not a module, and ruff is told so: {@code --stdin-filename} gives it a name
 * ending in {@code .py} so it applies Python rules, while nothing is ever written there. Code
 * split across cells means a cell can be a perfectly ordinary fragment that would not parse
 * alone - a continuation of a block, say - and ruff refuses those rather than guessing. That
 * refusal is reported as "this cell is not complete Python", not as a formatting failure,
 * because the cell is usually fine and the user simply asked at a strange moment.
 *
 * <p>Formatting is a text-in, text-out function, so almost all of this is testable: only
 * {@link #run} starts anything.
 */
final class Formatter {

    /** What pip is asked for. Pinned to a major line so a release cannot change the output. */
    static final String PACKAGE = "ruff>=0.14,<0.15";

    private Formatter() {
    }

    /** What came back: the formatted text, or why there is none. */
    record Formatted(Optional<String> text, Optional<String> problem) {

        static Formatted of(String text) {
            return new Formatted(Optional.of(text), Optional.empty());
        }

        static Formatted no(String problem) {
            return new Formatted(Optional.empty(), Optional.of(problem));
        }

        boolean worked() {
            return text.isPresent();
        }
    }

    /** Where ruff lives inside an environment, if it has been installed there. */
    static Optional<Path> in(Path interpreter) {
        if (interpreter == null || interpreter.getParent() == null) {
            return Optional.empty();
        }
        Path scripts = interpreter.getParent();
        for (String name : List.of("ruff.exe", "ruff")) {
            Path candidate = scripts.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * How to run ruff through the interpreter rather than its own launcher.
     *
     * <p>Always through {@code python -m ruff}, never {@code Scripts\ruff.exe}, for the
     * reason this project has met repeatedly: a launcher pip writes is unsigned, and on
     * Windows with Smart App Control it does not start at all. The interpreter is signed and
     * always does.
     */
    static List<String> command(Path interpreter, String cellName) {
        return List.of(interpreter.toString(), "-m", "ruff", "format",
                "--stdin-filename", cellName, "-");
    }

    /** What to run to put ruff into an environment, through the interpreter for the same reason. */
    static List<String> installCommand(Path interpreter) {
        return List.of(interpreter.toString(), "-m", "pip", "install", PACKAGE);
    }

    /**
     * Whether what ruff said is it refusing to parse rather than it failing.
     *
     * <p>Told apart by what ruff prints, because the exit code is the same either way: a
     * syntax complaint names the error, and anything else is a tool that did not work.
     */
    static boolean cannotParse(String said) {
        if (said == null) {
            return false;
        }
        String lower = said.toLowerCase(java.util.Locale.ROOT);
        // "Failed to parse" is what ruff 0.14 actually prints; the rest are what it and
        // other formatters have said, kept so a wording change does not silently turn a
        // fragment back into a reported failure.
        return lower.contains("failed to parse") || lower.contains("cannot parse")
                || lower.contains("syntaxerror") || lower.contains("invalid syntax")
                || lower.contains("unexpected indentation") || lower.contains("expected an expression");
    }

    /** Whether the complaint is that ruff is not installed in this environment. */
    static boolean notInstalled(String said) {
        if (said == null) {
            return false;
        }
        return said.contains("No module named ruff") || said.contains("No module named 'ruff'");
    }

    /**
     * Formats one cell's text.
     *
     * <p>Off the UI thread: it starts a process. Unchanged text comes back as unchanged text
     * rather than as nothing, so the caller can decide whether replacing it is worth an undo
     * step.
     */
    static Formatted format(Path interpreter, String source, String cellName) {
        if (interpreter == null) {
            return Formatted.no("there is no Python interpreter for this project");
        }
        if (source == null || source.isBlank()) {
            return Formatted.of(source == null ? "" : source);
        }
        try {
            Process process = new ProcessBuilder(command(interpreter, cellName)).start();
            try (var in = process.getOutputStream()) {
                in.write(source.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Formatted.no("ruff did not finish");
            }
            if (process.exitValue() != 0) {
                if (notInstalled(err)) {
                    return Formatted.no("ruff is not installed in this environment");
                }
                if (cannotParse(err)) {
                    return Formatted.no("this cell is not complete Python on its own,"
                            + " so it was left alone");
                }
                return Formatted.no(err.isBlank() ? "ruff would not format this cell" : err.strip());
            }
            /* Never hand back nothing for something. A formatter that exits well and prints
               an empty document would, one line further on, replace the cell with emptiness -
               losing work to a tool whose entire job is to leave the code alone but tidier.
               ruff exits 2 when it cannot parse, so this should not happen; it costs one
               comparison to make sure it cannot. */
            if (out.isBlank() && !source.isBlank()) {
                return Formatted.no("ruff returned nothing, so the cell was left alone");
            }
            return Formatted.of(out);
        } catch (IOException e) {
            return Formatted.no(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Formatted.no("interrupted");
        }
    }

    /** A name to give ruff for a cell, so its messages say something recognisable. */
    static String nameFor(Path notebook, int cellIndex) {
        String file = notebook == null || notebook.getFileName() == null
                ? "notebook" : notebook.getFileName().toString();
        return file.replaceAll("\\.ipynb$", "") + "-cell-" + (cellIndex + 1) + ".py";
    }

    /** Runs the install, waiting for it. Off the UI thread. */
    static Optional<String> install(Path interpreter) {
        if (interpreter == null) {
            return Optional.of("there is no Python interpreter for this project");
        }
        return run(installCommand(interpreter), 10);
    }

    /** @return empty when the command succeeded, or what it said when it did not */
    private static Optional<String> run(List<String> command, int minutes) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(minutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return Optional.of("it did not finish");
            }
            return process.exitValue() == 0 ? Optional.empty()
                    : Optional.of(said.isBlank() ? "it failed" : said.strip());
        } catch (IOException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
    }
}
