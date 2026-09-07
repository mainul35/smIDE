package com.smide.api.debug;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Every breakpoint in the IDE, whatever language the file is.
 *
 * <p>The core owns them - it draws them in the gutter and remembers them between runs -
 * and a debugger plugin reads them when a session starts and follows
 * {@link #addListener} while it runs, so a breakpoint added mid-session takes effect.
 */
public interface Breakpoints {

    List<Breakpoint> all();

    List<Breakpoint> inFile(Path file);

    Optional<Breakpoint> at(Path file, int line);

    /** Adds a breakpoint if the line has none, removes it if it has one. */
    void toggle(Path file, int line);

    void add(Breakpoint breakpoint);

    void remove(Path file, int line);

    /** Replaces the breakpoint on that line, for enabling or setting a condition. */
    void update(Breakpoint breakpoint);

    void removeAll();

    /** Called after any change, with the file that changed. */
    void addListener(Consumer<Path> listener);
}
