package com.smide.plugins.langgraph;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * How to start the langgraph CLI on this machine: the program, or the interpreter and the
 * module, whichever will actually run.
 *
 * <p>A launcher installed by pip is an unsigned executable, and on Windows that is enough for
 * Smart App Control to refuse to start it - the same refusal smIDE's own launcher meets. The
 * refusal happens when the process starts, so there is nothing about the file that says in
 * advance that it is blocked; it has to be tried. What always works where the launcher does not
 * is the interpreter itself, which is signed, running the CLI as a module - so that is the
 * fallback, and it is also the answer where the launcher was never installed but the package
 * was.
 *
 * <p>{@link #prefer} is the choice and knows nothing about processes, so it can be tested.
 * {@link #works} is the part that has to start something, and remembers what it learnt: a
 * blocked launcher stays blocked, while something that is merely not installed yet is asked
 * about again a couple of minutes later, so installing it does not need the IDE restarted.
 */
final class CliLaunch {

    /**
     * How the CLI is asked to run as a module.
     *
     * <p>{@code langgraph_cli} and not {@code langgraph_cli.cli}: the second one is importable
     * and exits 0 while printing nothing and doing nothing, because running the module that
     * defines the command group does not call it. It would pass any check that only looked at
     * the exit code, and then a dev server asked for in the Run window would appear to start
     * and immediately finish, having served nothing. That is why {@link #works} wants to be
     * answered rather than merely exited from.
     */
    static final List<List<String>> MODULE_FORMS = List.of(List.of("-m", "langgraph_cli"));

    /** How long a no is believed; a yes is believed for good. */
    private static final Duration FORGET = Duration.ofMinutes(2);

    private static final Map<String, Attempt> TRIED = new ConcurrentHashMap<>();

    /** What was learnt about one command, and when. */
    private record Attempt(boolean works, long at) {
    }

    private CliLaunch() {
    }

    /**
     * What to put in front of {@code dev}, {@code build} or {@code up}.
     *
     * @param launcher    the langgraph program, where there is one
     * @param interpreter the project's Python, where there is one
     * @param works       whether a command, run with {@code --help}, answers
     * @return the command prefix, or empty when neither way of starting it works
     */
    static Optional<List<String>> prefer(Optional<Path> launcher, Optional<Path> interpreter,
                                         Predicate<List<String>> works) {
        if (launcher.isPresent() && works.test(List.of(launcher.get().toString(), "--help"))) {
            return Optional.of(List.of(launcher.get().toString()));
        }
        if (interpreter.isEmpty()) {
            return Optional.empty();
        }
        for (List<String> form : MODULE_FORMS) {
            List<String> command = new ArrayList<>(List.of(interpreter.get().toString()));
            command.addAll(form);
            List<String> asked = new ArrayList<>(command);
            asked.add("--help");
            if (works.test(asked)) {
                return Optional.of(List.copyOf(command));
            }
        }
        return Optional.empty();
    }

    /** The same, starting things for real. */
    static Optional<List<String>> prefer(Optional<Path> launcher, Optional<Path> interpreter) {
        return prefer(launcher, interpreter, CliLaunch::works);
    }

    /**
     * Whether a command is the CLI: started, exited well, and said something.
     *
     * <p>A launcher Smart App Control has blocked fails to start, which arrives here as an
     * exception. A module that is not installed starts the interpreter and ends with a non-zero
     * exit and a traceback. A module that is installed but is not the command group exits 0 and
     * prints nothing. All three are a no - the last one is why saying something is part of the
     * question and not just exiting 0.
     */
    static boolean works(List<String> command) {
        String key = String.join(" ", command);
        Attempt known = TRIED.get(key);
        if (known != null && (known.works() || System.nanoTime() - known.at() < FORGET.toNanos())) {
            return known.works();
        }
        boolean answered = ask(command);
        TRIED.put(key, new Attempt(answered, System.nanoTime()));
        return answered;
    }

    private static boolean ask(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Read as it comes: a full pipe nobody empties is a process that never finishes.
            String said = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                return false;
            }
            return process.exitValue() == 0 && !said.isBlank();
        } catch (IOException | RuntimeException e) {
            // Could not be started: not there, or not allowed to run.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Forgets what was learnt, so the next question starts something again. */
    static void forget() {
        TRIED.clear();
    }
}
