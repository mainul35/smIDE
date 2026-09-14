package com.smide.core;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Notices when the window stops answering and writes down what the UI thread was doing.
 *
 * <p>A freeze can only be fixed once someone knows where the time went, and by the time
 * anyone says "closing a tab is slow" the moment has passed - and it may not happen at all
 * on another machine, with other projects. So a watchdog asks the JavaFX thread for a
 * heartbeat every 100 ms; when one goes unanswered for a second it samples that thread's
 * stack until it answers, then saves the samples under {@code ~/.smide/logs/freezes}: the
 * smIDE frames the thread was stuck in, most frequent first, and the whole stacks.
 * IntelliJ keeps freeze reports for the same reason.
 */
public final class FreezeReporter {

    /** How long the window may go without answering before it counts as frozen. */
    static final long THRESHOLD_MS = 1000;
    private static final long TICK_MS = 100;
    /** Reports kept; older ones are deleted. */
    private static final int KEEP = 20;
    /** About five minutes of samples; a longer freeze is described by its first five. */
    private static final int MAX_SAMPLES = 3000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final Consumer<String> status;
    private final long graceMillis;
    /** Set by the first heartbeat answered; nothing counts as a freeze before it. */
    private volatile Thread fxThread;
    private volatile boolean pending;
    private volatile long postedAt;
    private volatile long answeredAt;
    private volatile boolean stopped;
    private Thread watchdog;

    /**
     * @param dir         where reports go
     * @param status      told, in a sentence, that a report was written
     * @param graceMillis how long after {@link #start()} to begin watching
     */
    public FreezeReporter(Path dir, Consumer<String> status, long graceMillis) {
        this.dir = dir;
        this.status = status;
        this.graceMillis = graceMillis;
    }

    public FreezeReporter(Path dir, Consumer<String> status) {
        this(dir, status, 0);
    }

    public void start() {
        watchdog = new Thread(this::watch, "smide-freeze-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public void stop() {
        stopped = true;
        if (watchdog != null) {
            watchdog.interrupt();
        }
    }

    private void watch() {
        try {
            Thread.sleep(graceMillis);
        } catch (InterruptedException e) {
            return;
        }
        List<StackTraceElement[]> samples = new ArrayList<>();
        long frozenSince = 0;
        while (!stopped) {
            long before = System.nanoTime();
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if ((System.nanoTime() - before) / 1_000_000 > THRESHOLD_MS) {
                /* The watchdog itself was held up - the machine slept, or a debugger
                   suspended every thread. That says nothing about the window. */
                samples = new ArrayList<>();
                frozenSince = 0;
                postedAt = System.nanoTime();
                continue;
            }
            if (!pending) {
                if (frozenSince != 0) {
                    report((answeredAt - frozenSince) / 1_000_000, samples);
                    samples = new ArrayList<>();
                    frozenSince = 0;
                }
                pending = true;
                postedAt = System.nanoTime();
                try {
                    Platform.runLater(() -> {
                        // The first answer comes after whatever startup queued: that is not a freeze.
                        fxThread = Thread.currentThread();
                        answeredAt = System.nanoTime();
                        pending = false;
                    });
                } catch (IllegalStateException e) {
                    return; // the toolkit has exited
                }
                continue;
            }
            Thread fx = fxThread;
            if (fx != null && (System.nanoTime() - postedAt) / 1_000_000 >= THRESHOLD_MS) {
                if (frozenSince == 0) {
                    frozenSince = postedAt;
                }
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(fx.getStackTrace());
                }
            }
        }
    }

    private void report(long millis, List<StackTraceElement[]> samples) {
        if (samples.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("freeze-" + STAMP.format(LocalDateTime.now()) + "-" + millis + "ms.txt");
            Files.writeString(file, describe(millis, samples));
            prune();
            System.err.println("smIDE: the window did not respond for " + millis + " ms; see " + file);
            status.accept(String.format("The window did not respond for %.1f s. What it was doing: %s",
                    millis / 1000.0, file));
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not write a freeze report: " + e);
        }
    }

    /** The report: how long, where in smIDE the thread was, then each distinct stack. */
    static String describe(long millis, List<StackTraceElement[]> samples) {
        Map<String, Integer> own = new LinkedHashMap<>();
        Map<String, Integer> stacks = new LinkedHashMap<>();
        for (StackTraceElement[] stack : samples) {
            own.merge(innermostOwnFrame(stack), 1, Integer::sum);
            stacks.merge(format(stack), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        out.append("smIDE freeze report\n");
        out.append("The JavaFX thread did not answer for ").append(millis).append(" ms.\n");
        out.append(samples.size()).append(" stack samples, one every ").append(TICK_MS)
                .append(" ms once it had been ").append(THRESHOLD_MS).append(" ms.\n");
        out.append("Java ").append(System.getProperty("java.version")).append(", ")
                .append(System.getProperty("os.name")).append("\n");
        out.append("\nWhere in smIDE the thread was, by samples:\n");
        sorted(own).forEach(e -> out.append(String.format("%6d  %s%n", e.getValue(), e.getKey())));
        out.append("\nStacks, the most frequent first:\n");
        sorted(stacks).forEach(e -> out.append("\n--- ").append(e.getValue()).append(" of ")
                .append(samples.size()).append(" samples\n").append(e.getKey()));
        return out.toString();
    }

    private static String innermostOwnFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("com.smide.")) {
                return frame.toString();
            }
        }
        return stack.length == 0 ? "(no stack)" : "(outside smIDE) " + stack[0];
    }

    private static String format(StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(stack.length, 80); i++) {
            out.append("\tat ").append(stack[i]).append("\n");
        }
        return out.toString();
    }

    private static Stream<Map.Entry<String, Integer>> sorted(Map<String, Integer> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed());
    }

    private void prune() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> reports = files.filter(p -> p.getFileName().toString().startsWith("freeze-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path old : reports.subList(Math.min(KEEP, reports.size()), reports.size())) {
                Files.deleteIfExists(old);
            }
        }
    }
}
