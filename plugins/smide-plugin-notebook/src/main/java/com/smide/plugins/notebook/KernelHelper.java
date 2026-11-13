package com.smide.plugins.notebook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts the kernel helper on disk, where an interpreter can run it.
 *
 * <p>The script lives in the plugin's jar, and {@code python a-jar-entry.py} is not a
 * thing, so it is written out. It goes under {@code ~/.smide/tools/notebook} rather than
 * into the project: it is part of the IDE, not of anybody's repository, and a file smIDE
 * wrote should never turn up in someone's {@code git status}.
 *
 * <p>Written every time rather than only when missing. The alternative is a version check,
 * and the failure mode of getting that wrong is a stale helper talking a protocol the Java
 * side no longer speaks, which presents as a notebook that will not run for reasons nothing
 * logs. The file is a few kilobytes and this happens once per kernel.
 */
final class KernelHelper {

    static final String RESOURCE = "/com/smide/plugins/notebook/kernel_bridge.py";
    static final String FILE_NAME = "kernel_bridge.py";

    private KernelHelper() {
    }

    /**
     * Writes the helper under the IDE's home and returns where it went.
     *
     * @param smideHome {@code ide.homeDir()}
     */
    static Path install(Path smideHome) throws IOException {
        Path folder = smideHome.resolve("tools").resolve("notebook");
        Files.createDirectories(folder);
        Path target = folder.resolve(FILE_NAME);
        try (InputStream source = KernelHelper.class.getResourceAsStream(RESOURCE)) {
            if (source == null) {
                throw new IOException("the kernel helper is missing from the plugin");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** The helper's text, for a test that wants to check it without writing it anywhere. */
    static String text() throws IOException {
        try (InputStream source = KernelHelper.class.getResourceAsStream(RESOURCE)) {
            if (source == null) {
                throw new IOException("the kernel helper is missing from the plugin");
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * What to tell someone whose project cannot run a notebook yet, and what to run.
     *
     * <p>The exact command, with this project's interpreter in it and not the word
     * "python", because the whole difficulty is that there is more than one Python on the
     * machine and installing into the wrong one changes nothing. {@code -m pip} and not
     * {@code pip}: the {@code pip.exe} in a virtual environment is another unsigned
     * launcher, and on a machine with Smart App Control on it will not run.
     */
    static String installCommand(Path interpreter) {
        String python = interpreter == null ? "python" : quoted(interpreter.toString());
        return python + " -m pip install ipykernel";
    }

    private static String quoted(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }
}
