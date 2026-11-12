package com.smide;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where smIDE writes what is not part of a project.
 *
 * <p>There were five copies of this decision, and they did not all agree: four read
 * {@code smide.userHome}, one read plain {@code user.home}, so a second copy of the IDE
 * pointed somewhere else still wrote its freeze reports and read its interface size from
 * the first copy's folder.
 */
class SmIdeHomeTest {

    @AfterEach
    void putThePropertiesBack() {
        System.clearProperty("smide.home");
        System.clearProperty("smide.userHome");
    }

    @Test
    void withoutAnythingSetItIsTheDotFolderInTheUsersHome() {
        System.clearProperty("smide.home");
        System.clearProperty("smide.userHome");

        assertEquals(Path.of(System.getProperty("user.home"), ".smide").toAbsolutePath().normalize(),
                SmIdeHome.dir());
    }

    @Test
    void smideHomeIsTheDirectoryItself() {
        System.setProperty("smide.home", "C:/tmp/second-copy");

        assertEquals(Path.of("C:/tmp/second-copy").toAbsolutePath().normalize(), SmIdeHome.dir());
        assertTrue(SmIdeHome.dir().toString().endsWith("second-copy"),
                "no .smide appended to a directory that was named outright");
    }

    @Test
    void smideUserHomeStandsInForTheUsersHome() {
        System.setProperty("smide.userHome", "C:/tmp/pretend-user");

        assertEquals(Path.of("C:/tmp/pretend-user/.smide").toAbsolutePath().normalize(),
                SmIdeHome.dir());
    }

    @Test
    void theDirectoryNamedOutrightWins() {
        System.setProperty("smide.userHome", "C:/tmp/pretend-user");
        System.setProperty("smide.home", "C:/tmp/second-copy");

        assertEquals(Path.of("C:/tmp/second-copy").toAbsolutePath().normalize(), SmIdeHome.dir());
    }

    @Test
    void anEmptyPropertyIsNotAnAnswer() {
        System.setProperty("smide.home", "   ");

        assertEquals(Path.of(System.getProperty("user.home"), ".smide").toAbsolutePath().normalize(),
                SmIdeHome.dir());
    }

    @Test
    void aRelativeHomeIsMadeAbsoluteRatherThanFollowingTheWorkingDirectory() {
        System.setProperty("smide.home", "testhome");

        assertTrue(SmIdeHome.dir().isAbsolute(), "was " + SmIdeHome.dir());
    }

    @Test
    void everythingInsideItGoesWithIt() {
        System.setProperty("smide.home", "C:/tmp/second-copy");

        assertEquals(SmIdeHome.dir().resolve("logs").resolve("freezes"),
                SmIdeHome.resolve("logs", "freezes"));
        assertEquals(SmIdeHome.dir().resolve("settings.json"), SmIdeHome.resolve("settings.json"));
    }
}
