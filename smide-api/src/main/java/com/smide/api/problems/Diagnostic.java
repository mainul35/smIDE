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
}
