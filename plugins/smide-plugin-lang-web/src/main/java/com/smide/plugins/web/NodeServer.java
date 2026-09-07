package com.smide.plugins.web;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A language server that npm publishes as a command: typescript-language-server and the
 * three servers VS Code extracts from its own bundle for HTML, CSS and JSON.
 *
 * @param serverId    the id under which the IDE tracks it
 * @param displayName what the status bar calls it
 * @param executable  the launcher npm writes into {@code node_modules/.bin}
 * @param packages    what to install
 * @param arguments   arguments after the executable, usually {@code --stdio}
 */
record NodeServer(String serverId, String displayName, String executable, List<String> packages,
                  List<String> arguments) implements LanguageServerLauncher {

    @Override
    public String serverId() {
        return serverId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean isInstalled(Ide ide) {
        return NpmTools.locate(ide, executable).isPresent();
    }

    /**
     * Tells typescript-language-server where TypeScript itself is.
     *
     * <p>It looks for tsserver in the project being edited and refuses to start without
     * it - "Could not find a valid TypeScript installation" - which is right for a
     * TypeScript project and wrong for the loose .ts file someone opens to read. The copy
     * installed alongside the server is named explicitly, and a project with its own
     * TypeScript still wins, because the server prefers the workspace's.
     */
    @Override
    public Object initializationOptions(Ide ide, Workspace workspace) {
        if (!"typescript-language-server".equals(serverId)) {
            return null;
        }
        Path modules = NpmTools.prefix(ide).resolve("node_modules");
        // The copy installed for the server, or the one another server brought with it.
        for (Path candidate : List.of(
                modules.resolve("typescript").resolve("lib").resolve("tsserver.js"),
                modules.resolve("vscode-langservers-extracted").resolve("node_modules")
                        .resolve("typescript").resolve("lib").resolve("tsserver.js"))) {
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return Map.of("tsserver", Map.of("path", candidate.toString()));
            }
        }
        return null;
    }

    @Override
    public Optional<InstallRecipe> installRecipe() {
        return Optional.of(NpmTools.install(
                "Installs " + String.join(", ", packages) + " with npm into " + "~/.smide/tools/node."
                        + " Node.js must be installed.",
                packages.toArray(String[]::new)));
    }

    @Override
    public List<String> command(Ide ide, Workspace workspace) {
        Path exe = NpmTools.locate(ide, executable)
                .orElseThrow(() -> new IllegalStateException(displayName + " is not installed"));
        List<String> command = new ArrayList<>();
        /* A .cmd shim is a batch file, not an executable, so Windows needs a shell to run
           it. cmd /c also keeps the child's stdio pipes, which the protocol runs over. */
        if (NpmTools.WINDOWS && exe.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".cmd")) {
            command.add(System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
            command.add("/c");
        }
        command.add(exe.toString());
        command.addAll(arguments);
        return command;
    }
}
