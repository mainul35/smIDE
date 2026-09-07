package com.smide.plugins.terminal;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.util.EventBus;
import com.smide.api.util.Events;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Terminal plugin: a tool window of shell sessions in pseudo-terminals, actions to
 * open and clear them, and a settings page for the shell and font size.
 *
 * <p>The emulator is JetBrains' JediTerm (Swing) hosted in a {@code SwingNode}; the
 * pseudo-terminal is pty4j (ConPTY on Windows).
 */
public final class TerminalPlugin implements Plugin {

    private Ide ide;
    private ThemedSettingsProvider settings;
    private TerminalToolWindow toolWindow;
    private EventBus.Subscription themeSubscription;
    private volatile boolean stopped;

    @Override
    public void start(PluginContext context) {
        ide = context.ide();
        settings = new ThemedSettingsProvider(ide);
        toolWindow = new TerminalToolWindow(ide, settings);
        context.registerToolWindow(toolWindow);

        // Colours: refresh the provider and repaint every terminal when the theme flips.
        themeSubscription = ide.events().subscribe(Events.ThemeChanged.class, event -> restyle());
        // Font size: the settings dialog writes the key on Apply.
        ide.settings().addListener(key -> {
            if (!stopped && ThemedSettingsProvider.FONT_SIZE_KEY.equals(key)) {
                restyle();
            }
        });

        context.registerAction(Action.of("terminal.new", "New Terminal")
                .description("Open a shell in the active workspace")
                .icon("fth-terminal")
                .menu("Tools")
                .order(300)
                .perform(ctx -> toolWindow.openTerminal(toolWindow.defaultDirectory())));

        context.registerAction(Action.of("terminal.openIn", "Open in Terminal")
                .description("Open a shell in the selected folder")
                .icon("fth-terminal")
                .contextMenu("explorer")
                .order(300)
                .enabledWhen(ctx -> ctx.selectedFile().isPresent())
                .perform(ctx -> ctx.selectedFile().ifPresent(path -> toolWindow.openTerminal(folderOf(path)))));

        context.registerAction(Action.of("terminal.clear", "Clear Terminal")
                .description("Clear the current terminal's screen and scrollback")
                .icon("fth-trash-2")
                .menu("Tools")
                .order(301)
                .enabledWhen(ctx -> toolWindow.hasSessions())
                .perform(ctx -> {
                    toolWindow.clearSelected();
                    ide.toolWindows().show(TerminalToolWindow.ID);
                }));

        context.registerSettingsPage(new TerminalSettingsPage(ide));
    }

    @Override
    public void stop() {
        stopped = true;
        if (themeSubscription != null) {
            themeSubscription.cancel();
            themeSubscription = null;
        }
        if (toolWindow != null) {
            toolWindow.disposeAll();
        }
    }

    private void restyle() {
        settings.refresh();
        toolWindow.restyleAll();
    }

    /** A file's folder; a folder itself. */
    private static Path folderOf(Path path) {
        return Files.isDirectory(path) ? path : path.getParent();
    }
}
