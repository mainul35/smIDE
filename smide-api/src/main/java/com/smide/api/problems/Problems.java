package com.smide.api.problems;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** The Problems tool window and the editors' underlines, fed by language servers and builds. */
public interface Problems {

    /** Replaces every diagnostic this source reported for the file. May be called from any thread. */
    void set(String source, Path file, List<Diagnostic> diagnostics);

    /** Drops everything the source reported. */
    void clear(String source);

    List<Diagnostic> forFile(Path file);

    List<Diagnostic> all();

    void addListener(Consumer<Path> fileChanged);
}
