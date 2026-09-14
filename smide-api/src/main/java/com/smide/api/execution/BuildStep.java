package com.smide.api.execution;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Something that has to finish before a program can start - configuring, compiling - run
 * from {@link RunConfiguration#prepare}, which is off the UI thread.
 *
 * <p>Run as a separate process rather than chained into one shell command line, which is
 * what compile-then-run would otherwise need: quoting paths with spaces through cmd and sh
 * differently is where such chains break. Progress shows in the status bar; a failure
 * carries the last lines of output, where compilers put the reason.
 */
public final class BuildStep {

    private static final int TAIL = 40;

    private BuildStep() {
    }

    public static void run(Ide ide, List<String> command, Path directory, Map<String, String> environment, String title)
            throws IOException, InterruptedException {
        StatusBar.Progress progress = ide.statusBar().progress(title, false);
        List<String> tail = new ArrayList<>();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > TAIL) {
                        tail.remove(0);
                    }
                    if (progress != null) {
                        progress.update(line, -1);
                    }
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException(title + " failed with exit code " + code
                        + (tail.isEmpty() ? "." : ":\n" + String.join("\n", tail.subList(Math.max(0, tail.size() - 15), tail.size()))));
            }
        } finally {
            if (progress != null) {
                progress.done();
            }
        }
    }
}
