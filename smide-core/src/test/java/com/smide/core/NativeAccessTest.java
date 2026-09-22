package com.smide.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * smIDE calls no native code of its own, and if it ever must, it will not be through JNI.
 *
 * <p>Everything outside the JVM is another process or a socket, which is what keeps a language
 * server's crash from being smIDE's crash. Should that ever have to change - a platform API with
 * no Java equivalent - the way in is the Foreign Function &amp; Memory API: no C toolchain in the
 * build, no native library to ship per platform, and memory access that is checked rather than
 * hoped for. See arc42 ADR-005.
 *
 * <p>This is here because that decision is invisible in the code it governs. Nothing looks wrong
 * about a {@code System.loadLibrary} until the day somebody builds on a platform nobody compiled
 * for.
 */
class NativeAccessTest {

    /** {@code native} as a method modifier, not the word in a sentence or a file name. */
    private static final Pattern NATIVE_METHOD = Pattern.compile(
            "(?m)^[^*/\\n]*\\bnative\\s+[\\w<>\\[\\].]+\\s+\\w+\\s*\\(");

    private static final List<String> JNI = List.of("System.loadLibrary(", "System.load(");

    @Test
    void nothingInSmideLoadsANativeLibrary() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String jni : JNI) {
                if (text.contains(jni)) {
                    found.add(file + ": " + jni);
                }
            }
            if (NATIVE_METHOD.matcher(text).find()) {
                found.add(file + ": a native method");
            }
        }

        assertTrue(found.isEmpty(),
                "smIDE reaches native code through java.lang.foreign, not JNI - see arc42 ADR-005."
                        + " Found: " + found);
    }

    /** Every Java source of smIDE's own, across all the modules. */
    private static List<Path> sources() throws IOException {
        Path root = repository();
        if (root == null) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .toList();
        }
    }

    /** The top of the build, found by walking up from wherever the test was run. */
    private static Path repository() {
        for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
            if (Files.isDirectory(at.resolve("smide-api")) && Files.isDirectory(at.resolve("plugins"))) {
                return at;
            }
        }
        return null;
    }
}
