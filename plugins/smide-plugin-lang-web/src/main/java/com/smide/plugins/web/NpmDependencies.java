package com.smide.plugins.web;

import com.smide.api.problems.Diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a package.json asks for, and whether it is installed.
 *
 * <p>A package named in {@code dependencies} that is not in {@code node_modules} is reported on
 * its own characters - a misspelt name, a package added to the file by hand and never installed.
 *
 * <p>Only when something is installed. A project freshly cloned has no {@code node_modules} at
 * all, and every line of its package.json would be red for the perfectly ordinary reason that
 * nobody has run an install yet; that is not a mistake in the file and is not worth saying.
 */
public final class NpmDependencies {

    public static final String SOURCE = "npm";

    /** The blocks a package name in them is a dependency of this project. */
    private static final List<String> BLOCKS =
            List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

    /** {@code "name": "version"} inside one of those blocks. */
    private static final Pattern ENTRY = Pattern.compile("\"(@?[A-Za-z0-9_.\\-/]+)\"\\s*:\\s*\"([^\"]*)\"");

    private NpmDependencies() {
    }

    public static boolean isPackageJson(Path file) {
        return file != null && file.getFileName() != null && file.getFileName().toString().equals("package.json");
    }

    public static List<Diagnostic> problemsIn(Path file, String text) {
        Path modules = file.toAbsolutePath().getParent() == null ? null
                : file.toAbsolutePath().getParent().resolve("node_modules");
        if (modules == null || !Files.isDirectory(modules)) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        for (int[] span : missing(text, modules)) {
            int[] start = lineColumn(text, span[0]);
            int[] end = lineColumn(text, span[1]);
            String name = text.substring(span[0], span[1]);
            out.add(new Diagnostic(file, start[0], start[1], end[0], end[1], Diagnostic.Severity.ERROR,
                    "Cannot resolve " + name + ": it is not in node_modules. Install it to use it;"
                            + " if the install has run, the name is wrong.",
                    SOURCE, Diagnostic.UNRESOLVED));
        }
        return out;
    }

    /** The name spans of packages that are named in the file but not in node_modules. */
    private static List<int[]> missing(String text, Path modules) {
        List<int[]> out = new ArrayList<>();
        for (String block : BLOCKS) {
            int[] range = blockRange(text, block);
            if (range == null) {
                continue;
            }
            Matcher entry = ENTRY.matcher(text.subSequence(range[0], range[1]));
            while (entry.find()) {
                String name = entry.group(1);
                if (installed(modules, name) || isLocal(entry.group(2))) {
                    continue;
                }
                out.add(new int[] {range[0] + entry.start(1), range[0] + entry.end(1)});
            }
        }
        return out;
    }

    /** A version that names somewhere rather than a version: a folder, a repository, a tarball. */
    private static boolean isLocal(String version) {
        return version.startsWith("file:") || version.startsWith("link:") || version.startsWith("workspace:")
                || version.startsWith("git") || version.contains("://");
    }

    private static boolean installed(Path modules, String name) {
        Path installed = modules;
        for (String part : name.split("/")) {
            installed = installed.resolve(part);
        }
        return Files.isDirectory(installed);
    }

    /**
     * Where one block's braces are, found by counting them.
     *
     * <p>The file is JSON, and a dependency block is one level of braces; counting is enough to
     * find its end and keeps this to what it needs - the names in one block - without asking the
     * rest of the file to be valid while it is being typed.
     */
    private static int[] blockRange(String text, String block) {
        int at = text.indexOf('"' + block + '"');
        if (at < 0) {
            return null;
        }
        int open = text.indexOf('{', at);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[] {open, i};
                }
            }
        }
        return null;
    }

    private static int[] lineColumn(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }
}
