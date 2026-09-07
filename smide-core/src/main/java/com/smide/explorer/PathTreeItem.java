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

    private final boolean directory;
    private boolean loaded;
    private final Consumer<Path> onLoaded;

    public PathTreeItem(Path path, Consumer<Path> onLoaded) {
        super(path);
        this.directory = Files.isDirectory(path);
        this.onLoaded = onLoaded;
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
        List<Path> entries = list(getValue());
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
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
