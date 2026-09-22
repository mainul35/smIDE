package com.smide.core;

import com.smide.api.ui.Notifications;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a full heap is worth saying something about.
 *
 * <p>Written after a session filled two gigabytes over two hours and the Java runtime stopped it
 * without anything having warned the developer.
 */
class MemoryWatchTest {

    /** A heap whose numbers the test moves by hand. */
    static final class HandHeap implements MemoryWatch.Heap {

        long used;
        long max = 1000;

        @Override
        public long used() {
            return used;
        }

        @Override
        public long max() {
            return max;
        }
    }

    /** Everything said to the developer, in order. */
    static final class Said implements Notifications {

        final List<String> titles = new ArrayList<>();

        @Override
        public void info(String title, String message, NotificationAction... actions) {
            titles.add("info: " + title);
        }

        @Override
        public void warn(String title, String message, NotificationAction... actions) {
            titles.add(title + " - " + message);
        }

        @Override
        public void error(String title, String message, NotificationAction... actions) {
            titles.add("error: " + title);
        }

        @Override
        public List<Notification> history() {
            return List.of();
        }
    }

    private final HandHeap heap = new HandHeap();
    private final Said said = new Said();
    private final MemoryWatch watch = new MemoryWatch(said, heap);

    @Test
    void aHeapWithRoomInItIsNotWorthMentioning() {
        heap.used = 500;

        for (int i = 0; i < 20; i++) {
            assertFalse(watch.look());
        }

        assertTrue(said.titles.isEmpty());
    }

    @Test
    void aMomentaryPeakIsNotAnAlarm() {
        // Full, then collected: what every heap does, all day.
        for (int i = 0; i < 10; i++) {
            heap.used = 960;
            assertFalse(watch.look());
            heap.used = 400;
            assertFalse(watch.look());
        }

        assertTrue(said.titles.isEmpty(), said.titles.toString());
    }

    @Test
    void aHeapThatStaysFullIsSaidOnce() {
        heap.used = 950;

        for (int i = 0; i < MemoryWatch.HIGHS_BEFORE_WARNING - 1; i++) {
            assertFalse(watch.look(), "not yet: it may still be collected");
        }
        assertTrue(watch.look(), "four readings in a row is not a passing peak");

        for (int i = 0; i < 20; i++) {
            assertFalse(watch.look(), "and it is not said again");
        }
        assertEquals(1, said.titles.size());
        assertTrue(said.titles.get(0).contains("nearly out of memory"), said.titles.get(0));
    }

    @Test
    void itIsSaidAgainIfTheTroubleComesBack() {
        heap.used = 950;
        for (int i = 0; i < MemoryWatch.HIGHS_BEFORE_WARNING; i++) {
            watch.look();
        }
        assertEquals(1, said.titles.size());

        // Recovered - something was closed, or a collection found room.
        heap.used = 400;
        watch.look();

        heap.used = 980;
        for (int i = 0; i < MemoryWatch.HIGHS_BEFORE_WARNING; i++) {
            watch.look();
        }
        assertEquals(2, said.titles.size());
    }

    @Test
    void aRuntimeWithoutALimitIsLeftAlone() {
        heap.max = 0;
        heap.used = 1_000_000;

        assertFalse(watch.look());
        assertTrue(said.titles.isEmpty());
    }
}
