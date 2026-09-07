package com.smide.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The workspaces opened before, one absolute path per line, most recent first, in
 * {@code ~/.smide/workspaces.txt}. Best effort throughout: a history that cannot be
 * read or written is a missing convenience, never a reason to fail an open.
 */
public final class WorkspaceHistory {

    public static final int MAX_ENTRIES = 15;

    private final Path file;

    public WorkspaceHistory(Path file) {
        this.file = file;
    }

    public List<Path> list() {
        List<Path> paths = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Path path = Path.of(trimmed);
                if (Files.isDirectory(path)) {
                    paths.add(path);
                }
            } catch (RuntimeException e) {
                // A malformed line is skipped rather than failing the list.
            }
        }
        return paths;
    }

    public void record(Path root) {
        if (root == null) {
            return;
        }
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(root.toAbsolutePath().normalize().toString());
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        write(entries.stream().limit(MAX_ENTRIES).toList());
    }

    public void remove(Path root) {
        String target = root.toAbsolutePath().normalize().toString();
        List<String> kept = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.equals(target)) {
                kept.add(trimmed);
            }
        }
        write(kept);
    }

    private List<String> readLines() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void write(List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not write " + file + " - " + e);
        }
    }
}
