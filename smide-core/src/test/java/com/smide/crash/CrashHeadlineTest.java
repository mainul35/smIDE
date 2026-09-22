package com.smide.crash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Saying what the runtime's crash log actually says.
 *
 * <p>The report filed as smIDE issue 2 was headed "The Java runtime stopped smIDE the last time
 * it ran", which was true and told nobody anything. Ten lines further down the same log said
 * {@code OutOfMemoryError}, ten times over, in the last ten seconds of the session.
 */
class CrashHeadlineTest {

    @Test
    void aFullHeapIsNamedAsOne() {
        String log = """
                Event: 6989.432 Thread 0x0000017bd8d10e10 Exception <a 'java/lang/OutOfMemoryError'>
                thrown [s\\src\\hotspot\\share\\gc\\shared\\memAllocator.cpp, line 136]
                """;

        assertEquals("smIDE ran out of memory, and the Java runtime stopped it.",
                CrashReporter.headline(log));
    }

    @Test
    void soIsRunawayRecursion() {
        assertEquals("Something in smIDE called itself until the stack ran out.",
                CrashReporter.headline("Exception <a 'java/lang/StackOverflowError'>"));
    }

    @Test
    void aNativeFaultIsSaidToBeOne() {
        assertEquals("The Java runtime stopped smIDE: a fault in native code.",
                CrashReporter.headline("#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007fff"));
        assertEquals("The Java runtime stopped smIDE: a fault in native code.",
                CrashReporter.headline("#  SIGSEGV (0xb) at pc=0x00007fff"));
    }

    @Test
    void andWhatCannotBeNamedIsNotGuessedAt() {
        assertEquals("The Java runtime stopped smIDE the last time it ran.",
                CrashReporter.headline("# A fatal error has been detected.\n# Problematic frame:\n"));
    }
}
