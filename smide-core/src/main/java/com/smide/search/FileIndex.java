package com.smide.search;

import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every file under every open workspace, for Go to File and Find in Files. Built on a
 * background thread on first use and invalidated by directory changes; build output and
 * dependency directories are skipped because nobody goes to a file there on purpose.
 */
public final class FileIndex {

    public static final Set<String> SKIPPED = Set.of(
            ".git", ".svn", ".hg", "target", "build", "out", "dist", "node_modules", ".idea", ".gradle",
            "__pycache__", ".venv", "venv", "bin", "obj", ".vs", ".cache", ".smide", ".mvn", ".next", ".nuxt");
    private static final int MAX_FILES = 200_000;

    private final Map<Path, List<Path>> byRoot = new ConcurrentHashMap<>();

    public List<Path> files(List<Workspace> workspaces) {
        List<Path> out = new ArrayList<>();
        for (Workspace w : workspaces) {
            out.addAll(byRoot.computeIfAbsent(w.root(), FileIndex::walk));
        }
        return out;
    }

    public void invalidate(Path root) {
        byRoot.remove(root);
    }

    public void invalidateAll() {
        byRoot.clear();
    }

    private static List<Path> walk(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file);
                    }
                    return files.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot index " + root + ": " + e);
        }
        return files;
    }

    /**
     * How well a query matches a file name: higher is better, zero is no match.
     * Prefix beats substring beats subsequence; camel humps ({@code FTP} for
     * FileTreePanel) count as a subsequence with a bonus for landing on word starts.
     */
    public static int score(String query, Path file, Path root) {
        if (query.isEmpty()) {
            return 1;
        }
        String name = file.getFileName().toString();
        String lowerName = name.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (lowerName.equals(q)) {
            return 1000;
        }
        if (lowerName.startsWith(q)) {
            return 800 - name.length();
        }
        int idx = lowerName.indexOf(q);
        if (idx >= 0) {
            return 600 - idx - name.length() / 4;
        }
        int humps = subsequence(q, name);
        if (humps > 0) {
            return 300 + humps;
        }
        String rel = root == null ? file.toString() : root.relativize(file).toString();
        String lowerRel = rel.toLowerCase(Locale.ROOT).replace('\\', '/');
        String qPath = q.replace('\\', '/');
        if (lowerRel.contains(qPath)) {
            return 200 - rel.length() / 8;
        }
        if (subsequence(qPath.replace("/", ""), rel.replace("/", "").replace("\\", "")) > 0) {
            return 50;
        }
        return 0;
    }

    private static int subsequence(String q, String text) {
        int qi = 0;
        int bonus = 0;
        for (int i = 0; i < text.length() && qi < q.length(); i++) {
            char c = text.charAt(i);
            if (Character.toLowerCase(c) == q.charAt(qi)) {
                if (i == 0 || Character.isUpperCase(c) || !Character.isLetterOrDigit(text.charAt(i - 1))) {
                    bonus += 10;
                }
                qi++;
            }
        }
        return qi == q.length() ? 1 + bonus : 0;
    }
}
