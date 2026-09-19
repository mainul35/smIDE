package com.smide.api.lang;

import com.smide.api.Ide;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Something a language's projects need on the machine - a compiler, an interpreter, an SDK -
 * which the plugin can find but cannot install by itself.
 *
 * <p>A plugin registers one, and the IDE asks for it at the moment it matters: when a
 * project that needs it is opened and it is not there. That is the difference between a
 * Go plugin that silently offers no way to run anything and one that says, the first time
 * a Go project is opened, what is missing and where to get it.
 *
 * <p>{@link #isNeededBy} and {@link #locate} are called off the UI thread and may look at
 * the file system.
 */
public interface Toolchain {

    /** Stable id, used to ask about each project only once: {@code go}. */
    String id();

    /** How it reads to a person: {@code Go toolchain}. */
    String displayName();

    /** Whether a project at this root is one that needs it - a go.mod, a .go file near the top. */
    boolean isNeededBy(Path root);

    /** The installed tool itself - the go command, the python interpreter - if it is on this machine. */
    Optional<Path> locate(Ide ide);

    /** What does not work without it, in a sentence: "Running and testing Go code need it." */
    String purpose();

    /** Where to get it, or null. */
    default String downloadUrl() {
        return null;
    }

    /** The setting that points at an installation chosen by hand, or null when there is none. */
    default String homeSetting() {
        return null;
    }

    /** Whether a folder someone picked is an installation this can use. */
    default boolean accepts(Path home) {
        return true;
    }

    /**
     * Whether this is what runs a file of this kind - a .go file, a .py file - so that an
     * editor opened on one can say, across its top, that it is missing.
     */
    default boolean runsFile(Path file) {
        return false;
    }

    /**
     * The newest version the IDE can download and unpack by itself for this machine, the way
     * IntelliJ offers "Download Python...", or empty when this one has to be installed by
     * hand. Only a portable archive qualifies - one that works where it is unpacked, needs no
     * installer and touches nothing else on the machine. Called off the UI thread: finding the
     * newest version means asking whoever publishes it.
     */
    default Optional<Download> latestDownload(Ide ide) throws java.io.IOException {
        return Optional.empty();
    }

    /**
     * An archive of one version, for this operating system and processor.
     *
     * @param version as a person reads it: {@code 1.25.1}
     * @param url     the archive, a {@code .zip} or {@code .tar.gz}
     * @param sha256  its checksum as its publisher gives it, or null where none is published
     * @param size    in bytes, or -1 when not known before downloading
     * @param source  who it comes from, as the question should name them: {@code go.dev}
     */
    record Download(String version, String url, String sha256, long size, String source) {
    }
}
