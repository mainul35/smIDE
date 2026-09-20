package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.ui.Notifications;
import com.smide.api.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a project opens, asks for any toolchain it needs that is not on the machine.
 *
 * <p>Once per toolchain and project, and only when both are true - the project needs it
 * and it cannot be found - because a notice about Go in a Java project, or about a Go that
 * is already installed, teaches people to dismiss notices. The looking happens off the UI
 * thread: finding out whether a folder is a Go project can mean walking it.
 */
public final class ToolchainCheck {

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final ToolchainInstaller installer;
    private final Set<String> asked = ConcurrentHashMap.newKeySet();

    public ToolchainCheck(Ide ide, ExtensionRegistry registry, ToolchainInstaller installer) {
        this.ide = ide;
        this.registry = registry;
        this.installer = installer;
    }

    /** Whether this toolchain's absence has already been raised for this reason. */
    public boolean firstTime(String key) {
        return asked.add(key);
    }

    /** Looks at a project that has just opened. */
    public void check(Workspace workspace) {
        List<Toolchain> toolchains = registry.toolchains();
        if (toolchains.isEmpty() || workspace == null) {
            return;
        }
        ide.window().runInBackground(() -> {
            for (Toolchain toolchain : toolchains) {
                try {
                    if (!toolchain.isNeededBy(workspace.root())) {
                        continue;
                    }
                    installer.adopt(toolchain);
                    if (toolchain.locate(ide).isPresent()) {
                        continue;
                    }
                    if (asked.add(toolchain.id() + "|" + workspace.root())) {
                        ide.window().runLater(() -> ask(toolchain, workspace));
                    }
                } catch (RuntimeException e) {
                    // One plugin's broken check is no reason to skip the others.
                    System.err.println("smIDE: toolchain check " + toolchain.id() + " failed: " + e);
                }
            }
        });
    }

    private void ask(Toolchain toolchain, Workspace workspace) {
        // "the Go toolchain", but "Gradle": a name that is already a name takes no article.
        String name = toolchain.displayName().toLowerCase(java.util.Locale.ROOT).contains("toolchain")
                ? "the " + toolchain.displayName() : toolchain.displayName();
        ask(toolchain, workspace.name() + " needs " + name + ", which was not found on this machine. "
                + toolchain.purpose());
    }

    /** The offer to install a missing toolchain, for whatever reason it turned out to be missing. */
    public void ask(Toolchain toolchain, String reason) {
        List<Notifications.NotificationAction> actions = new ArrayList<>();
        // Downloaded and set up by the IDE where it can be, the page in a browser where it cannot.
        if (ToolchainInstaller.canInstall(toolchain) || toolchain.downloadUrl() != null) {
            actions.add(new Notifications.NotificationAction("Download " + ToolchainInstaller.shortName(toolchain) + "...",
                    () -> installer.offer(toolchain)));
        }
        if (toolchain.homeSetting() != null) {
            actions.add(new Notifications.NotificationAction("Set location...", () -> installer.chooseHome(toolchain)));
        }
        actions.add(new Notifications.NotificationAction("Not now", () -> {
        }));
        ide.notifications().warn(toolchain.displayName() + " not found", reason,
                actions.toArray(new Notifications.NotificationAction[0]));
    }
}
