package com.smide.api.debug;

import java.nio.file.Path;

/**
 * A line the debugger should stop on.
 *
 * <p>Lines are zero-based, like everywhere else in the API. A breakpoint belongs to a
 * file rather than to a debug session, so it survives the session, the editor being
 * closed, and the IDE being restarted.
 *
 * @param condition an expression that must be true to stop, or null to stop always
 */
public record Breakpoint(Path file, int line, boolean enabled, String condition) {

    public Breakpoint(Path file, int line) {
        this(file, line, true, null);
    }

    public Breakpoint withEnabled(boolean value) {
        return new Breakpoint(file, line, value, condition);
    }

    public Breakpoint withCondition(String value) {
        return new Breakpoint(file, line, enabled, value == null || value.isBlank() ? null : value);
    }

    /** How it reads in a list: {@code Main.java:12}. */
    public String label() {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return name + ":" + (line + 1);
    }
}
