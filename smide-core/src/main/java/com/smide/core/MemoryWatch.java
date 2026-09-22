package com.smide.core;

import com.smide.api.ui.Notifications;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Saying so before the heap runs out, rather than after.
 *
 * <p>A session ran for close to two hours, filled its two gigabytes, threw {@code
 * OutOfMemoryError} ten times in the last ten seconds, and then the Java runtime died inside its
 * own event loop - which the developer experienced as the window vanishing. Nothing had warned
 * them, and nothing could have: the IDE never looked.
 *
 * <p>So it looks. Not at a single reading, because a heap that is briefly full is a heap about to
 * be collected, but at whether it stays full: {@link #HIGHS_BEFORE_WARNING} readings in a row
 * above {@link #WARN_AT} means the garbage collector has had its chance and there is nothing left
 * to reclaim. Said once, and not again until the heap has recovered, because an IDE that nags
 * about memory teaches people to ignore warnings about memory.
 */
public final class MemoryWatch {

    /** Full enough to be worth saying, as a share of the heap the runtime may grow to. */
    static final double WARN_AT = 0.90;
    /** And low enough afterwards to be worth saying again if it comes back. */
    static final double CALM_AT = 0.75;
    /** How many readings in a row, so a moment before a collection is not an alarm. */
    static final int HIGHS_BEFORE_WARNING = 4;

    private static final long EVERY_SECONDS = 30;

    /** Where the numbers come from, so this can be tested without filling a real heap. */
    public interface Heap {

        long used();

        long max();
    }

    private final Heap heap;
    private final Notifications notifications;
    private int highs;
    private boolean said;

    public MemoryWatch(Notifications notifications) {
        this(notifications, new RuntimeHeap());
    }

    MemoryWatch(Notifications notifications, Heap heap) {
        this.notifications = notifications;
        this.heap = heap;
    }

    /** Starts looking, on a daemon thread that costs nothing between readings. */
    public void start() {
        // A real thread on purpose: something watching for trouble should not be waiting its turn
        // on the scheduler that the trouble is in. See arc42 §8.2.
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "smide-memory");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::look, EVERY_SECONDS, EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /** One reading. True when this was the one that warned. */
    boolean look() {
        long max = heap.max();
        if (max <= 0) {
            return false;
        }
        double full = (double) heap.used() / max;
        if (full < CALM_AT) {
            highs = 0;
            said = false;
            return false;
        }
        if (full < WARN_AT) {
            return false;
        }
        highs++;
        if (said || highs < HIGHS_BEFORE_WARNING) {
            return false;
        }
        said = true;
        notifications.warn("smIDE is nearly out of memory",
                gigabytes(heap.used()) + " of " + gigabytes(max) + " is in use and has stayed that"
                        + " way. Close what you are not reading - console tabs keep everything their"
                        + " process printed - or restart smIDE before the Java runtime stops it.");
        return true;
    }

    private static String gigabytes(long bytes) {
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** The real heap, as the runtime reports it. */
    private static final class RuntimeHeap implements Heap {

        @Override
        public long used() {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        @Override
        public long max() {
            return Runtime.getRuntime().maxMemory();
        }
    }
}
