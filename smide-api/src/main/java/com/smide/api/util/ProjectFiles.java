package com.smide.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Looking through a project for the files that matter to a language - programs, build
 * files, tests - without wandering into what does not.
 *
 * <p>Bounded in depth and in files looked at, because it runs whenever the run list is
 * opened, and a checkout with node_modules or a build output folder can hold more files
 * than anyone would wait for.
 */
public final class ProjectFiles {

    /** Folders holding dependencies, build output or caches rather than a project's own files. */
    public static final Set<String> SKIPPED = Set.of("node_modules", "vendor", "target", "__pycache__", "venv",
            "site-packages", "testdata", "obj", "dist", "Pods", "DerivedData");

    private ProjectFiles() {
    }

    /**
     * The files under a root that match, breadth first, sorted.
     *
     * @param maxDepth how many folder levels below the root to go into; 0 is the root's own files
     * @param maxFiles how many files to look at before giving up
     */
    public static List<Path> find(Path root, int maxDepth, int maxFiles, Predicate<Path> wanted) {
        return find(root, maxDepth, maxFiles, Integer.MAX_VALUE, wanted);
    }

    /** Whether any file within that depth matches. */
    public static boolean any(Path root, int maxDepth, Predicate<Path> wanted) {
        return !find(root, maxDepth, 20_000, 1, wanted).isEmpty();
    }

    private static List<Path> find(Path root, int maxDepth, int maxFiles, int limit, Predicate<Path> wanted) {
        List<Path> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        Deque<Map.Entry<Path, Integer>> pending = new ArrayDeque<>();
        pending.add(new AbstractMap.SimpleEntry<>(root, 0));
        int seen = 0;
        while (!pending.isEmpty() && seen < maxFiles && out.size() < limit) {
            Map.Entry<Path, Integer> next = pending.poll();
            try (Stream<Path> children = Files.list(next.getKey())) {
                for (Path child : children.sorted().toList()) {
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        if (next.getValue() < maxDepth && !name.startsWith(".") && !SKIPPED.contains(name)) {
                            pending.add(new AbstractMap.SimpleEntry<>(child, next.getValue() + 1));
                        }
                    } else if (++seen <= maxFiles && wanted.test(child)) {
                        out.add(child);
                        if (out.size() >= limit) {
                            break;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // An unreadable folder has nothing to offer.
            }
        }
        out.sort(null);
        return out;
    }

    /** Whether a file's name ends with one of these extensions, given without the dot. */
    public static boolean hasExtension(Path file, String... extensions) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String extension : extensions) {
            if (name.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    /** The start of a file as text - enough to find a main function or a script block. */
    public static String head(Path file, int maxBytes) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Whether the start of a file matches a pattern. */
    public static boolean contains(Path file, Pattern pattern) {
        return pattern.matcher(head(file, 256_000)).find();
    }

    /** {@code a/b} with forward slashes, or an empty string for the same folder. */
    public static String relative(Path from, Path to) {
        try {
            return from.toAbsolutePath().normalize().relativize(to.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return to.toString();
        }
    }
}
