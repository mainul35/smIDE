package com.smide;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The supervisor's decisions: when the IDE comes back, how, and when it stops trying. */
class SupervisorTest {

    /** A clock the test moves by hand. */
    static final class HandClock extends Clock {
        Instant now = Instant.parse("2026-09-18T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Children that end with the given codes, one after another, remembering how each was started. */
    static final class Script implements Supervisor.Child {
        final Deque<Integer> codes;
        final List<String> modes = new ArrayList<>();
        final List<Boolean> firsts = new ArrayList<>();
        final HandClock clock;
        final Duration between;

        Script(HandClock clock, Duration between, Integer... codes) {
            this.clock = clock;
            this.between = between;
            this.codes = new ArrayDeque<>(Arrays.asList(codes));
        }

        @Override
        public int run(String mode, boolean first) {
            modes.add(mode);
            firsts.add(first);
            clock.now = clock.now.plus(between);
            return codes.isEmpty() ? 0 : codes.removeFirst();
        }
    }

    private final HandClock clock = new HandClock();

    @Test
    void closingTheIdeEndsTheSupervisorToo() throws Exception {
        Script children = new Script(clock, Duration.ofMinutes(10), 0);
        assertEquals(0, new Supervisor(clock).supervise(children));
        assertEquals(List.of("normal"), children.modes);
    }

    @Test
    void anIdeThatDiesIsStartedAgainAsItWas() throws Exception {
        // 134 is what a native crash ends with on Linux; -1073741819 is Windows's access violation.
        Script children = new Script(clock, Duration.ofSeconds(30), 134, -1073741819, 0);
        assertEquals(0, new Supervisor(clock).supervise(children));
        assertEquals(List.of("normal", "normal", "normal"), children.modes);
    }

    @Test
    void filesNamedOnTheCommandLineAreOpenedOnceNotAtEveryRestart() throws Exception {
        Script children = new Script(clock, Duration.ofSeconds(30), 134, 0);
        new Supervisor(clock).supervise(children);
        assertEquals(List.of(true, false), children.firsts);
    }

    @Test
    void afterThreeQuickDeathsItComesBackInSafeMode() throws Exception {
        Script children = new Script(clock, Duration.ofSeconds(20), 1, 1, 1, 0);
        new Supervisor(clock).supervise(children);
        assertEquals(List.of("normal", "normal", "normal", "safe"), children.modes);
    }

    @Test
    void afterFiveQuickDeathsItStopsAndSaysWhy() throws Exception {
        Script children = new Script(clock, Duration.ofSeconds(20), 1, 1, 1, 1, 139, 0);
        Supervisor supervisor = new Supervisor(clock);
        assertEquals(139, supervisor.supervise(children), "it ends as the last child did");
        assertEquals(5, children.modes.size(), "and starts no sixth");
        assertTrue(supervisor.log().get(supervisor.log().size() - 1).contains("Not starting it again"));
    }

    @Test
    void deathsFarApartDoNotAddUpToGivingUp() throws Exception {
        // One death every ten minutes, a dozen times: bad luck, not a loop.
        Integer[] codes = new Integer[13];
        Arrays.fill(codes, 1);
        codes[12] = 0;
        Script children = new Script(clock, Duration.ofMinutes(10), codes);
        assertEquals(0, new Supervisor(clock).supervise(children));
        assertEquals(13, children.modes.size());
        assertFalse(children.modes.contains("safe"));
    }

    @Test
    void aRestartTheIdeAskedForIsNotADeath() throws Exception {
        Script children = new Script(clock, Duration.ofSeconds(5),
                Supervisor.RESTART, Supervisor.RESTART, Supervisor.RESTART, Supervisor.RESTART,
                Supervisor.RESTART, Supervisor.RESTART, 0);
        assertEquals(0, new Supervisor(clock).supervise(children));
        assertEquals(7, children.modes.size());
        assertFalse(children.modes.contains("safe"));
    }

    @Test
    void safeModeIsWhatItAskedFor_andANormalRestartLeavesIt() throws Exception {
        Script children = new Script(clock, Duration.ofSeconds(5), Supervisor.RESTART_SAFE, Supervisor.RESTART, 0);
        new Supervisor(clock).supervise(children);
        assertEquals(List.of("normal", "safe", "normal"), children.modes);
    }

    @Test
    void theChildIsNotHandedTheLauncherStateItsParentWasStartedWith() {
        // What the packaged launcher on Linux leaves in the environment of the process it starts.
        java.util.Map<String, String> environment = new java.util.HashMap<>(java.util.Map.of(
                "_JPACKAGE_LAUNCHER", "0",
                "LD_LIBRARY_PATH", ":/home/me/.local/opt/smide/lib/app",
                "PATH", "/usr/bin",
                "JAVA_TOOL_OPTIONS", "-Dsmide.userHome=/tmp/h"));
        Supervisor.prepare(environment, "safe");

        assertFalse(environment.containsKey("_JPACKAGE_LAUNCHER"),
                "inherited, it makes the child's launcher start Java with nothing to run");
        assertEquals("1", environment.get("SMIDE_SUPERVISED"));
        assertEquals("safe", environment.get("SMIDE_MODE"));
        assertEquals("/usr/bin", environment.get("PATH"), "everything else is passed on as it was");
        assertEquals("-Dsmide.userHome=/tmp/h", environment.get("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void aClassPathRunIsRepeatedFromItsParts() {
        List<String> first = Supervisor.command(new String[] {"README.md"}, true);
        List<String> again = Supervisor.command(new String[] {"README.md"}, false);
        assertTrue(first.get(0).contains("java"), first.get(0));
        assertTrue(first.contains("-cp"));
        assertTrue(first.contains(Launcher.class.getName()));
        assertEquals("README.md", first.get(first.size() - 1));
        assertFalse(again.contains("README.md"), "a restart brings back the session, not the command line");
    }
}
