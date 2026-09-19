package com.smide.explorer;

import com.smide.api.Ide;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens what is inside a jar the trees show: editors work on files, so an entry is written
 * out under {@code ~/.smide/libraries/jars/<jar>/} - where library sources go, opened
 * read-only in the project the reader is in - and remembered, so that Select Opened File
 * can find its way back into the jar.
 */
final class JarEntries {

    /** A file taken out of a jar, and where in the jar it came from. */
    private static final Map<Path, Path> EXTRACTED = new ConcurrentHashMap<>();

    private final Ide ide;

    JarEntries(Ide ide) {
        this.ide = ide;
    }

    /** Opens a file on disk as it is, and one inside a jar by taking it out first. */
    void open(Path path) {
        if (path.getFileSystem() == FileSystems.getDefault()) {
            ide.editors().open(path);
            return;
        }
        ide.window().runInBackground(() -> {
            try {
                Path file = extract(path);
                if (file != null) {
                    Platform.runLater(() -> ide.editors().open(file));
                }
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> ide.notifications().error("Cannot open " + path, String.valueOf(e.getMessage())));
            }
        });
    }

    /**
     * Where in a jar an opened file came from - one taken out of a jar in the trees, or a
     * class followed into a library - as a path inside that jar; the file itself when it is
     * from no jar. When the entry is not in the jar any more, the jar.
     */
    Path origin(Path file) {
        Path known = EXTRACTED.get(file.toAbsolutePath().normalize());
        if (known != null) {
            return known;
        }
        java.util.Optional<com.smide.editor.LibraryOrigins.Origin> origin =
                com.smide.editor.LibraryOrigins.of(ide.homeDir(), file);
        if (origin.isEmpty()) {
            return file;
        }
        Path root = PathTreeItem.archiveRoot(origin.get().archive());
        if (root == null) {
            return origin.get().archive();
        }
        Path entry = root.resolve(origin.get().entry());
        return Files.exists(entry) ? entry : origin.get().archive();
    }

    /** The entry written out; a class as its source, from the sources jar beside, when there is one. */
    private Path extract(Path entry) throws IOException {
        Path jar = PathTreeItem.archiveOf(entry);
        if (jar == null) {
            return null;
        }
        String inside = entry.toString();
        Path from = entry;
        if (inside.endsWith(".class")) {
            String name = jar.getFileName().toString();
            Path sources = jar.resolveSibling(name.substring(0, name.lastIndexOf('.')) + "-sources.jar");
            Path sourcesRoot = Files.isRegularFile(sources) ? PathTreeItem.archiveRoot(sources) : null;
            String java = inside.substring(0, inside.length() - ".class".length());
            int inner = java.indexOf('$', java.lastIndexOf('/'));
            java = (inner < 0 ? java : java.substring(0, inner)) + ".java";
            Path source = sourcesRoot == null ? null : sourcesRoot.resolve(java.startsWith("/") ? java.substring(1) : java);
            if (source == null || !Files.isRegularFile(source)) {
                Path shown = jar;
                Platform.runLater(() -> ide.statusBar().message(PathTreeItem.name(entry)
                        + " is compiled code, and there is no sources jar beside " + shown.getFileName()));
                return null;
            }
            from = source;
            jar = sources;
            inside = source.toString();
        }
        Path out = ide.homeDir().resolve("libraries").resolve("jars").resolve(jar.getFileName().toString())
                .resolve(inside.startsWith("/") ? inside.substring(1) : inside).toAbsolutePath().normalize();
        if (!Files.isRegularFile(out) || Files.size(out) != Files.size(from)) {
            Files.createDirectories(out.getParent());
            if (Files.exists(out)) {
                out.toFile().setWritable(true);
            }
            Files.copy(from, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().setReadOnly();
        }
        EXTRACTED.put(out, from);
        com.smide.editor.LibraryOrigins.remember(ide.homeDir(), out, jar, from.toString());
        return out;
    }
}
