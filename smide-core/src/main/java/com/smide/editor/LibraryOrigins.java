package com.smide.editor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Where each read-only library source under {@code ~/.smide/libraries} came from: the jar or
 * zip, and the entry in it. Editors need a file, so a class followed into a library is
 * written out; this is how Select Opened File finds it again in its library - after a
 * restart too, since the tab comes back and the jar it came from has to be known then.
 */
public final class LibraryOrigins {

    /** An entry in an archive: {@code javafx/application/Application.java} in its sources jar. */
    public record Origin(Path archive, String entry) {
    }

    private static final String FILE = "origins.properties";
    private static Properties loaded;
    private static Path loadedFrom;

    private LibraryOrigins() {
    }

    /** Remembers where a file written under {@code libraries} came from. */
    public static synchronized void remember(Path homeDir, Path written, Path archive, String entry) {
        Properties p = load(homeDir);
        String key = key(written);
        String value = archive.toAbsolutePath().normalize() + "!" + (entry.startsWith("/") ? entry.substring(1) : entry);
        if (value.equals(p.getProperty(key))) {
            return;
        }
        p.setProperty(key, value);
        Path file = homeDir.resolve("libraries").resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(out, "Where each library source here came from: archive!entry");
            }
        } catch (IOException e) {
            System.err.println("smIDE: cannot write " + file + ": " + e);
        }
    }

    /** Where a file came from, if it is one the IDE wrote out of a library. */
    public static synchronized Optional<Origin> of(Path homeDir, Path file) {
        String value = load(homeDir).getProperty(key(file));
        if (value == null) {
            return Optional.empty();
        }
        int bang = value.lastIndexOf('!');
        if (bang < 0) {
            return Optional.empty();
        }
        Path archive = Path.of(value.substring(0, bang));
        return Files.isRegularFile(archive) ? Optional.of(new Origin(archive, value.substring(bang + 1))) : Optional.empty();
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static Properties load(Path homeDir) {
        if (loaded != null && homeDir.equals(loadedFrom)) {
            return loaded;
        }
        Properties p = new Properties();
        Path file = homeDir.resolve("libraries").resolve(FILE);
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("smIDE: cannot read " + file + ": " + e);
            }
        }
        loaded = p;
        loadedFrom = homeDir;
        return p;
    }
}
