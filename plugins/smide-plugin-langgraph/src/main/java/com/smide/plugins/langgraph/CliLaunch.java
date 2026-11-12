package com.smide.plugins.langgraph;

import java.io.IOException;
import java.nio.file.Files;
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
 *
 * <p>That distinction is the whole of why {@link Outcome} has three values rather than being a
 * boolean. Windows raises a notification every time it refuses a program, so asking a blocked
 * launcher again every couple of minutes does not merely waste a process - it puts a warning on
 * the user's screen every couple of minutes, about their own IDE, for as long as the IDE is
 * open. A refusal is also the one answer that cannot change while the file does not: the
 * policy is about what the file is, not about what is installed. So a refusal is remembered
 * for as long as the file is the one that was refused, and no longer.
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

    /** How long a no is believed; a yes, and a refusal, are believed for good. */
    private static final Duration FORGET = Duration.ofMinutes(2);

    /**
     * What Windows says when a policy refuses a program, as it reaches us through the exception
     * message - 4551 is Smart App Control, 1260 the older group-policy refusal.
     *
     * <p>Matching on the text of a message is not something to do lightly. It is done here
     * because the alternative is worse: Java gives a plain {@link IOException} for every failure
     * to start, so without the code in the message a refusal cannot be told from a file that is
     * not there, and the two want opposite answers - one is permanent, the other is exactly what
     * installing the thing would fix. If a future Java carries the code some better way, this
     * should use it instead.
     */
    private static final List<String> REFUSED_CODES = List.of("error=4551", "error=1260");

    private static final Map<String, Attempt> TRIED = new ConcurrentHashMap<>();

    /** How a command answered when it was tried. */
    enum Outcome {
        /** It ran, exited well, and said something: this is the CLI. */
        ANSWERED,
        /** Windows would not start it at all. Permanent, while the file is unchanged. */
        REFUSED,
        /** It started and did not answer, or is not installed yet. Worth asking again later. */
        SILENT
    }

    /**
     * What was learnt about one command, when, and what the program looked like at the time.
     *
     * @param stamp identifies the file that was tried, so that reinstalling it is noticed
     */
    private record Attempt(Outcome outcome, long at, String stamp) {
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
     * question and not just exiting 0. Only the first is remembered for good, because only the
     * first cannot be put right by installing something.
     */
    static boolean works(List<String> command) {
        String key = String.join(" ", command);
        String stamp = stampOf(command);
        Attempt known = TRIED.get(key);
        if (known != null && known.stamp().equals(stamp) && believable(known)) {
            return known.outcome() == Outcome.ANSWERED;
        }
        Outcome outcome = ask(command);
        TRIED.put(key, new Attempt(outcome, System.nanoTime(), stamp));
        return outcome == Outcome.ANSWERED;
    }

    /** Whether what was learnt still stands: a yes and a refusal do, a silence expires. */
    private static boolean believable(Attempt known) {
        return known.outcome() != Outcome.SILENT
                || System.nanoTime() - known.at() < FORGET.toNanos();
    }

    /**
     * What the program a command runs looks like now: its size and when it changed.
     *
     * <p>This is what lets a refusal be remembered for good without that becoming a lie. The
     * file that was refused will go on being refused; a file installed over it is a different
     * file and deserves to be asked about. An unreadable path stamps as empty, which simply
     * means the answer is kept the way it always was.
     */
    private static String stampOf(List<String> command) {
        if (command.isEmpty()) {
            return "";
        }
        try {
            Path program = Path.of(command.get(0));
            return Files.size(program) + "@" + Files.getLastModifiedTime(program).toMillis();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Outcome ask(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Read as it comes: a full pipe nobody empties is a process that never finishes.
            String said = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                return Outcome.SILENT;
            }
            return process.exitValue() == 0 && !said.isBlank() ? Outcome.ANSWERED : Outcome.SILENT;
        } catch (IOException e) {
            // Could not be started: refused by a policy, or simply not there.
            return refusal(e) ? Outcome.REFUSED : Outcome.SILENT;
        } catch (RuntimeException e) {
            return Outcome.SILENT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.SILENT;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Whether a failure to start was a policy refusing the file rather than anything else. */
    static boolean refusal(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            String said = at.getMessage();
            if (said != null && REFUSED_CODES.stream().anyMatch(said::contains)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets what was learnt, so the next question starts something again. */
    static void forget() {
        TRIED.clear();
    }
}
