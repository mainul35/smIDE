package com.smide.plugins.python;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Microsoft's pyright, run as {@code pyright-langserver --stdio} from the shared npm prefix or PATH. */
final class PyrightLanguageServer implements LanguageServerLauncher {

    private static final String EXECUTABLE = "pyright-langserver";

    @Override
    public String serverId() {
        return "pyright";
    }

    @Override
    public String displayName() {
        return "Pyright";
    }

    @Override
    public boolean isInstalled(Ide ide) {
        return NpmTools.locate(ide, EXECUTABLE).isPresent();
    }

    @Override
    public Optional<InstallRecipe> installRecipe() {
        return Optional.of(NpmTools.install(
                "Install pyright with npm into ~/.smide/tools/node. Requires Node.js - the one on PATH, or one the IDE downloaded.", "pyright"));
    }

    @Override
    public java.util.Map<String, String> environment(Ide ide, Workspace workspace) {
        // Its launcher runs "node" by name: the Node.js the IDE downloaded or was pointed at goes first.
        return NpmTools.environment(ide);
    }
    
    @Override
    public List<String> command(Ide ide, Workspace workspace) {
        Path executable = NpmTools.locate(ide, EXECUTABLE)
                .orElseThrow(() -> new IllegalStateException("pyright is not installed"));
        return List.of(executable.toString(), "--stdio");
    }
}
