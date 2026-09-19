package com.smide.api.lang;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.util.List;

/**
 * Something one plugin adds to how another plugin's language server starts: Lombok as an
 * agent in the Java server, say. Plugins cannot reach each other's classes, so the server's
 * plugin asks for these by its {@link LanguageServerLauncher#serverId()} when it builds the
 * command line, and a plugin that is disabled simply contributes nothing.
 */
public interface LanguageServerContributor {

    /** The server this adds to: {@code jdtls}. */
    String serverId();

    /**
     * Options for the Java virtual machine the server runs in, for this project - empty when
     * the project needs none. Called off the UI thread, each time the server starts; it may
     * read the disk and fetch what it needs.
     */
    List<String> jvmArguments(Ide ide, Workspace workspace);
}
