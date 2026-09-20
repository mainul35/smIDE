package com.smide.api.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The toolchains plugins have registered, and what the IDE can do about a missing one: the
 * notice offering to download it, which a plugin needs when it finds out a tool is missing
 * at the moment it tries to use it - Gradle, when a project's wrapper cannot run.
 */
public interface Toolchains {

    List<Toolchain> all();

    Optional<Toolchain> byId(String id);

    /** Where a toolchain is on this machine, when it is there. Off the UI thread: it looks at disks. */
    Optional<Path> locate(String id);

    /**
     * Offers to download and set up a missing toolchain - "Gradle not found... Download Gradle...,
     * Set location..." - asking before anything is fetched. Once per toolchain and reason; may be
     * called from any thread.
     */
    void offerToInstall(String id, String reason);
}
