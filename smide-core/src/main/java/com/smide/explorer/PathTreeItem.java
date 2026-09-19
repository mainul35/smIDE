package com.smide.explorer;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A tree node that lists its directory the first time it is expanded, so a workspace
 * with {@code node_modules} inside does not stall the window on open.
 */
public final class PathTreeItem extends TreeItem<Path> {

    /** Jars and zips opened for browsing, kept open: the paths of what is in them live in these. */
    private static final java.util.Map<Path, java.nio.file.FileSystem> ARCHIVES = new java.util.concurrent.ConcurrentHashMap<>();

    private final boolean directory;
    private boolean loaded;
    private final Consumer<Path> onLoaded;
    private final String label;

    public PathTreeItem(Path path, Consumer<Path> onLoaded) {
        this(path, onLoaded, null);
    }

    /**
     * @param label what the tree shows instead of the file name - a library's name - or null
     */
    public PathTreeItem(Path path, Consumer<Path> onLoaded, String label) {
        super(path);
        this.directory = Files.isDirectory(path) || isArchive(path);
        this.onLoaded = onLoaded;
        this.label = label;
        if (directory) {
            // A placeholder so the disclosure arrow shows before the listing exists.
            super.getChildren().add(new TreeItem<>());
            expandedProperty().addListener((obs, was, now) -> {
                if (now && !loaded) {
                    reload();
                }
            });
        }
    }

    public boolean isDirectory() {
        return directory;
    }

    /** What the row says, when it is not the file name; null otherwise. */
    public String label() {
        return label;
    }

    /** A jar or zip on disk, which the tree opens like a folder, as IntelliJ does with a library's jar. */
    public static boolean isArchive(Path path) {
        if (path.getFileSystem() != java.nio.file.FileSystems.getDefault() || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war")) && Files.isRegularFile(path);
    }

    /** The inside of a jar, as a folder whose paths can be listed and read; null when it cannot be opened. */
    public static Path archiveRoot(Path archive) {
        Path key = archive.toAbsolutePath().normalize();
        try {
            java.nio.file.FileSystem fs = ARCHIVES.computeIfAbsent(key, k -> {
                try {
                    // Read only: browsing a library must never be able to change the jar in the repository.
                    return java.nio.file.FileSystems.newFileSystem(k, java.util.Map.of("accessMode", "readOnly"));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return fs.getRootDirectories().iterator().next();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The jar a path inside one comes from, or null for a path on disk. */
    public static Path archiveOf(Path path) {
        if (path.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
            return null;
        }
        for (java.util.Map.Entry<Path, java.nio.file.FileSystem> e : ARCHIVES.entrySet()) {
            if (e.getValue() == path.getFileSystem()) {
                return e.getKey();
            }
        }
        return null;
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public boolean isLeaf() {
        return !directory;
    }

    @Override
    public ObservableList<TreeItem<Path>> getChildren() {
        if (directory && !loaded && isExpanded()) {
            reload();
        }
        return super.getChildren();
    }

    /** (Re)reads the directory, keeping the items of children that are still there. */
    public void reload() {
        if (!directory) {
            return;
        }
        Path listed = getValue();
        if (isArchive(listed)) {
            listed = archiveRoot(listed);
        }
        List<Path> entries = listed == null ? List.of() : list(listed);
        List<TreeItem<Path>> existing = new ArrayList<>(super.getChildren());
        List<TreeItem<Path>> merged = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            TreeItem<Path> keep = null;
            for (TreeItem<Path> item : existing) {
                if (item instanceof PathTreeItem p && entry.equals(p.getValue())) {
                    keep = item;
                    break;
                }
            }
            merged.add(keep != null ? keep : new PathTreeItem(entry, onLoaded));
        }
        boolean first = !loaded;
        loaded = true;
        super.getChildren().setAll(merged);
        if (first && onLoaded != null) {
            onLoaded.accept(getValue());
        }
    }

    /** A path's last part, without the slash a folder inside a jar ends in. */
    static String name(Path p) {
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        return name.endsWith("/") && name.length() > 1 ? name.substring(0, name.length() - 1) : name;
    }

    /** Refreshes this directory and every loaded directory beneath it. */
    public void refreshDeep() {
        if (!directory || !loaded) {
            return;
        }
        reload();
        for (TreeItem<Path> child : super.getChildren()) {
            if (child instanceof PathTreeItem p) {
                p.refreshDeep();
            }
        }
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> name(p).toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
