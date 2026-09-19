package com.smide.api.util;

import com.smide.api.lang.LanguageServerLauncher.ProgressReporter;

import java.io.IOException;
import java.nio.file.Path;

/** Fetches and unpacks tools such as language servers into {@code ~/.smide/tools}. Background thread. */
public interface Downloads {

    /** {@code ~/.smide/tools}. */
    Path toolsDir();

    /** Downloads to {@code target}, creating parents; reports bytes as a fraction when the size is known. */
    void download(String url, Path target, ProgressReporter progress) throws IOException;

    /** Reads a small text resource such as a version file. */
    String fetchText(String url) throws IOException;

    /** Extracts {@code .tar.gz}, {@code .tgz} or {@code .zip} into {@code targetDir}. */
    void extract(Path archive, Path targetDir, ProgressReporter progress) throws IOException;

    /** Runs a command to completion, streaming output to the reporter; throws on a non-zero exit. */
    void runTool(java.util.List<String> command, Path workingDir, ProgressReporter progress) throws IOException;

    /** {@link #runTool(java.util.List, Path, ProgressReporter)} with these variables set - a PATH, say - over the IDE's own. */
    void runTool(java.util.List<String> command, Path workingDir, java.util.Map<String, String> environment,
                 ProgressReporter progress) throws IOException;
}
