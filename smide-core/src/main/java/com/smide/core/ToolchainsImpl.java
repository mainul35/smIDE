package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.lang.Toolchains;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The registered toolchains, and the offer to install one a plugin has just found missing. */
final class ToolchainsImpl implements Toolchains {

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final ToolchainCheck check;

    ToolchainsImpl(Ide ide, ExtensionRegistry registry, ToolchainCheck check) {
        this.ide = ide;
        this.registry = registry;
        this.check = check;
    }

    @Override
    public List<Toolchain> all() {
        return registry.toolchains();
    }

    @Override
    public Optional<Toolchain> byId(String id) {
        return all().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Path> locate(String id) {
        return byId(id).flatMap(t -> t.locate(ide));
    }

    @Override
    public void offerToInstall(String id, String reason) {
        byId(id).ifPresent(toolchain -> ide.window().runLater(() -> {
            // Once for each reason: a build run three times must not stack three notices.
            if (check.firstTime(id + "|" + reason)) {
                check.ask(toolchain, reason);
            }
        }));
    }
}
