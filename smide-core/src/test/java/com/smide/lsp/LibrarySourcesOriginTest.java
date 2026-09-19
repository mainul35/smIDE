package com.smide.lsp;

import com.smide.editor.LibraryOrigins.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which class in which jar a JDT {@code jdt://contents} URI names, for Select Opened File. */
class LibrarySourcesOriginTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLibraryClassOnWindows() {
        String uri = "jdt://contents/javafx-graphics-21-win.jar/javafx.application/Application.class"
                + "?=smide-core/%5C/C:%5C/Users%5C/mainu%5C/.m2%5C/repository%5C/org%5C/openjfx%5C/javafx-graphics"
                + "%5C/21%5C/javafx-graphics-21-win.jar=/maven.pomderived=/true=/=/maven.groupId=/org.openjfx=/"
                + "=/maven.artifactId=/javafx-graphics=/=/maven.version=/21=/%3Cjavafx.application(Application.class";
        Origin origin = LibrarySources.originOf(uri).orElseThrow();
        assertEquals(Path.of("C:/Users/mainu/.m2/repository/org/openjfx/javafx-graphics/21/javafx-graphics-21-win.jar"),
                origin.archive());
        assertEquals("javafx/application/Application.class", origin.entry());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aLibraryClassElsewhere() {
        String uri = "jdt://contents/gson-2.11.0.jar/com.google.gson/Gson.class"
                + "?=app/%5C/home%5C/me%5C/.m2%5C/repository%5C/com%5C/google%5C/code%5C/gson%5C/gson%5C/2.11.0"
                + "%5C/gson-2.11.0.jar=/maven.groupId=/com.google.code.gson=/%3Ccom.google.gson(Gson.class";
        Origin origin = LibrarySources.originOf(uri).orElseThrow();
        assertEquals(Path.of("/home/me/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"), origin.archive());
        assertEquals("com/google/gson/Gson.class", origin.entry());
    }

    @Test
    void aJdkClassIsItsSourceInTheJdksSrcZip() {
        String uri = "jdt://contents/java.base/java.lang/String.class"
                + "?=app/%5C/opt%5C/jdk-21%5C/lib%5C/jrt-fs.jar%60java.base=/javadoc_location=/x=/%3Cjava.lang(String.class";
        Origin origin = LibrarySources.originOf(uri).orElseThrow();
        assertTrue(origin.archive().endsWith(Path.of("jdk-21", "lib", "src.zip")), origin.archive().toString());
        assertEquals("java.base/java/lang/String.java", origin.entry());
    }

    @Test
    void nothingFromAUriThatDoesNotSay() {
        assertTrue(LibrarySources.originOf("jdt://contents/x.jar/p/T.class").isEmpty());
        assertTrue(LibrarySources.originOf("file:///C:/x/T.java").isEmpty());
        assertTrue(LibrarySources.originOf(null).isEmpty());
    }
}
