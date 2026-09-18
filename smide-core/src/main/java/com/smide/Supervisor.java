package com.smide;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Keeps smIDE running.
 *
 * <p>Most failures are caught inside the IDE and cost nothing but a dialog. One kind cannot
 * be: the Java runtime itself stopping - a crash in native code, the process being killed,
 * memory running out past the point of recovery. Nothing inside a process survives its own
 * end, so the only thing that can bring the IDE back is something outside it. That is this:
 * a small parent process that starts the IDE as a child, waits, and starts it again if it
 * ends any way other than the reader closing it.
 *
 * <p>How the child ended is read from its exit code:
 *
 * <ul>
 *   <li>0 - the reader closed smIDE. The parent ends too.
 *   <li>{@link #RESTART} - the IDE asked to be started again: Restart in the crash dialog,
 *       or Try Again after a failed start.
 *   <li>{@link #RESTART_SAFE} - the same, without plugins and without the last session.
 *   <li>anything else - it died. It is started again, and the last session comes back.
 * </ul>
 *
 * <p>A child that dies again at once would otherwise be restarted for ever, so deaths are
 * counted over a window of time. After {@link #SAFE_AFTER} the next start is in safe mode,
 * where the usual suspects - a plugin, a file the last session reopens - are left out.
 * After {@link #GIVE_UP_AFTER} the parent stops: something is wrong that restarting will
 * not fix, and a process that restarts forever is worse than one that stops and says so.
 */
public final class Supervisor {

    /** The child asks to be started again. EX_TEMPFAIL, which is roughly what it means. */
    public static final int RESTART = 75;
    /** The child asks to be started again in safe mode. */
    public static final int RESTART_SAFE = 76;

    /** Set in the child's environment: it is being supervised, so it need not supervise. */
    static final String SUPERVISED = "SMIDE_SUPERVISED";
    /** Set in the child's environment: {@code normal} or {@code safe}. */
    static final String MODE = "SMIDE_MODE";

    static final int SAFE_AFTER = 3;
    static final int GIVE_UP_AFTER = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);

    /** Starts the IDE and waits for it; returns how it ended. */
    interface Child {
        int run(String mode, boolean first) throws IOException, InterruptedException;
    }

    private final Clock clock;
    private final List<String> log = new ArrayList<>();

    Supervisor(Clock clock) {
        this.clock = clock;
    }

    /** Runs children until one is closed by the reader, or until restarting is plainly not helping. */
    int supervise(Child child) throws IOException, InterruptedException {
        Deque<Instant> deaths = new ArrayDeque<>();
        String mode = "normal";
        boolean first = true;
        while (true) {
            int code = child.run(mode, first);
            first = false;
            if (code == 0) {
                return 0;
            }
            if (code == RESTART) {
                note("smIDE asked to be started again.");
                mode = "normal";
                continue;
            }
            if (code == RESTART_SAFE) {
                note("smIDE asked to be started again in safe mode.");
                mode = "safe";
                continue;
            }
            Instant now = clock.instant();
            deaths.addLast(now);
            while (!deaths.isEmpty() && deaths.peekFirst().isBefore(now.minus(WINDOW))) {
                deaths.removeFirst();
            }
            if (deaths.size() >= GIVE_UP_AFTER) {
                note("smIDE stopped " + deaths.size() + " times in " + WINDOW.toMinutes()
                        + " minutes, the last time with exit code " + code + ". Not starting it again:"
                        + " restarting is not fixing whatever this is. The crash reports are in"
                        + " ~/.smide/logs/crashes.");
                return code;
            }
            mode = deaths.size() >= SAFE_AFTER ? "safe" : "normal";
            note("smIDE stopped unexpectedly (exit code " + code + "); starting it again"
                    + ("safe".equals(mode) ? " in safe mode, after " + deaths.size() + " failures." : "."));
        }
    }

    List<String> log() {
        return log;
    }

    private void note(String line) {
        log.add(line);
        System.err.println("smIDE supervisor: " + line);
    }

    // ------------------------------------------------------------------ launching

    /**
     * Whether this process should supervise rather than be the IDE.
     *
     * <p>Not when it is itself the supervised child, not when switched off with
     * {@code -Dsmide.supervise=false}, and not under a debugger: the debugger would be
     * attached to the parent, which has nothing in it worth debugging, while the child
     * could not listen on the same port.
     */
    static boolean shouldSupervise() {
        if (System.getenv(SUPERVISED) != null) {
            return false;
        }
        if (!Boolean.parseBoolean(System.getProperty("smide.supervise", "true"))) {
            return false;
        }
        return inputArguments().stream().noneMatch(a -> a.contains("jdwp"));
    }

    /** Runs the IDE as a child of this process, again and again as needed, then exits as it did. */
    static void runSupervised(String[] args) {
        Supervisor supervisor = new Supervisor(Clock.systemUTC());
        int code;
        try {
            code = supervisor.supervise((mode, first) -> {
                ProcessBuilder builder = new ProcessBuilder(command(args, first)).inheritIO();
                prepare(builder.environment(), mode);
                return builder.start().waitFor();
            });
        } catch (IOException | InterruptedException e) {
            /* The supervisor cannot start a child at all - the launcher moved, say. Rather
               than leave the reader with nothing, be the IDE instead, unsupervised. */
            System.err.println("smIDE supervisor: cannot start smIDE as a child (" + e + "); starting it here.");
            SmIdeApp.launchIde(args);
            return;
        }
        System.exit(code);
    }

    /**
     * The child's environment: this process's, told it is supervised and in which mode, and
     * without what the packaged launcher left behind for itself.
     *
     * <p>On Linux the launcher starts in two stages, and hands the second what it needs in
     * {@code _JPACKAGE_LAUNCHER}. The supervisor's own environment still has it. Passed on,
     * it tells the child's launcher that it is that second stage, so the launcher skips the
     * application's configuration and starts Java with nothing to run - and the child dies at
     * once, printing Java's usage. Found on a real machine, where every child did exactly
     * that until the supervisor gave up. Windows's launcher does not do this, which is why it
     * never showed there.
     */
    static void prepare(java.util.Map<String, String> environment, String mode) {
        environment.keySet().removeIf(name -> name.startsWith("_JPACKAGE"));
        environment.put(SUPERVISED, "1");
        environment.put(MODE, mode);
    }

    /**
     * How to start the IDE again, the way this process was started.
     *
     * <p>A packaged smIDE is started by its native launcher, which reads the JVM's options from
     * the application's own configuration, so the launcher is run again as it is. A run from a
     * class path is repeated from its parts: the same runtime, the same JVM options, the same
     * class path. Files named on the command line are opened by the first child only; a
     * restart brings back the session instead.
     */
    static List<String> command(String[] args, boolean first) {
        List<String> command = new ArrayList<>();
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && Files.isExecutable(Path.of(launcher))) {
            command.add(launcher);
        } else {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
            command.addAll(inputArguments());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Launcher.class.getName());
        }
        if (first) {
            command.addAll(Arrays.asList(args));
        }
        return command;
    }

    private static List<String> inputArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Whether this process is the supervised IDE, and so may ask to be restarted. */
    public static boolean supervised() {
        return System.getenv(SUPERVISED) != null;
    }

    /** Whether this process was started in safe mode. */
    public static boolean safeMode() {
        return "safe".equals(System.getenv(MODE)) || Boolean.getBoolean("smide.safeMode");
    }
}
