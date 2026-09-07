package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;
import com.smide.plugins.java.JavaTools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a Maven module and resolves its runtime classpath before an application runs.
 * Runs synchronously on a background thread; failure carries the tail of Maven's output.
 */
public final class MavenBuild {

    private static final String CLASSPATH_FILE = "target/smide-classpath.txt";

    private MavenBuild() {
    }

    /**
     * @param root      the workspace root (where the reactor lives)
     * @param moduleDir the module to compile; equal to root for a single-module build
     * @return the runtime classpath: target/classes plus dependencies
     */
    public static String compileAndClasspath(Ide ide, Path root, Path moduleDir, boolean skipCompile)
            throws IOException, InterruptedException {
        List<String> cmd = JavaTools.maven(ide, root);
        cmd.add("-q");
        cmd.add("-B");
        if (!moduleDir.equals(root)) {
            cmd.add("-pl");
            cmd.add(Forms.relative(root, moduleDir).replace('\\', '/'));
            cmd.add("-am");
        }
        if (!skipCompile) {
            cmd.add("compile");
        }
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=" + CLASSPATH_FILE);
        cmd.add("-Dmdep.includeScope=runtime");
        run(ide, cmd, root, "Building " + moduleDir.getFileName());
        Path file = moduleDir.resolve(CLASSPATH_FILE);
        String deps = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
        String classes = moduleDir.resolve("target/classes").toString();
        return deps.isEmpty() ? classes : classes + java.io.File.pathSeparator + deps;
    }

    /** Runs a Maven command to completion, reporting lines to the status bar; throws on failure. */
    public static void run(Ide ide, List<String> cmd, Path cwd, String title) throws IOException, InterruptedException {
        StatusBar.Progress progress = ide.statusBar().progress(title, false);
        List<String> tail = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
            pb.environment().put("JAVA_HOME", JavaTools.jdkHome(ide).toString());
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 60) {
                        tail.remove(0);
                    }
                    progress.update(line.length() > 80 ? line.substring(0, 80) : line, -1);
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                /* The first error naming a source file is what identifies the failure.
                   The whole tail went into a notification balloon that covered the
                   editor; the lines are on standard error for the full story. */
                tail.forEach(System.err::println);
                String first = tail.stream()
                        .filter(l -> l.contains("ERROR") && l.contains(".java"))
                        .findFirst()
                        .orElseGet(() -> tail.isEmpty() ? "" : tail.get(tail.size() - 1));
                throw new IOException("Maven exited with " + code + "\n" + first.strip());
            }
        } finally {
            progress.done();
        }
    }
}
