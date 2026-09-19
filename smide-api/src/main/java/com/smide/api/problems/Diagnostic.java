package com.smide.api.problems;

import java.nio.file.Path;

/**
 * One problem in one file. Lines and columns are zero-based; the end is exclusive.
 *
 * @param source who reported it, e.g. {@code jdtls} or {@code maven}
 */
public record Diagnostic(Path file,
                         int startLine,
                         int startColumn,
                         int endLine,
                         int endColumn,
                         Severity severity,
                         String message,
                         String source,
                         String code) {

    public enum Severity {
        ERROR, WARNING, INFO, HINT
    }

    /**
     * The {@code code} of a diagnostic that names something which cannot be found: a
     * dependency that is not in the local repository, a reference to nothing. It is drawn as
     * IntelliJ draws an unresolved reference - the name itself in red - rather than
     * underlined, because what is wrong is the name, not the code around it.
     */
    public static final String UNRESOLVED = "unresolved";
}
