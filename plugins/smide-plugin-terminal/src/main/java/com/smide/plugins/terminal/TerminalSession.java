package com.smide.plugins.terminal;

import com.jediterm.terminal.ui.TerminalWidget;
import com.jediterm.terminal.ui.TerminalWidgetListener;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.smide.api.Ide;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One terminal: a shell in a pseudo-terminal, a JediTerm widget reading it, and the
 * {@link SwingNode} that puts the widget in the JavaFX scene.
 *
 * <p>Threads: {@link #start} spawns the shell on the IDE's background pool, then builds
 * the Swing widget on the event dispatch thread and finally attaches it to the node.
 * JediTerm runs its own emulator thread that blocks reading the pty. The JavaFX thread is
 * never made to wait on any of them.
 */
final class TerminalSession {

    static final int INITIAL_COLUMNS = 120;
    static final int INITIAL_ROWS = 30;

    private final Ide ide;
    private final ThemedSettingsProvider settings;
    private final Path directory;
    private final String title;
    private final StackPane node = new StackPane();
    private final SwingNode swingNode = new SwingNode();
    private final Label status = new Label();
    private final AtomicBoolean disposed = new AtomicBoolean();

    /** Runs on the JavaFX thread when the shell exits or cannot start. */
    private Runnable onExit = () -> {
    };

    // Swing-side state; only touched on the event dispatch thread once created.
    private SmideTerminalWidget widget;
    private PtyTtyConnector connector;

    TerminalSession(Ide ide, ThemedSettingsProvider settings, Path directory, String title) {
        this.ide = ide;
        this.settings = settings;
        this.directory = directory;
        this.title = title;
        node.getStyleClass().add("terminal-session");
        node.setMinSize(0, 0);
        status.getStyleClass().add("empty-hint");
        status.setText("Starting " + title + "...");
        node.getChildren().addAll(status, swingNode);
        swingNode.setVisible(false);
    }

    Node node() {
        return node;
    }

    String title() {
        return title;
    }

    Path directory() {
        return directory;
    }

    void setOnExit(Runnable onExit) {
        this.onExit = onExit;
    }

    /** Spawns the shell and, when it is running, shows the emulator. Returns at once. */
    void start() {
        String[] command = ShellResolver.command(ide.settings());
        ide.window().runInBackground(() -> {
            PtyProcess process;
            try {
                process = new PtyProcessBuilder(command)
                        .setEnvironment(ShellResolver.environment())
                        .setDirectory(directory.toString())
                        .setConsole(false)
                        .setInitialColumns(INITIAL_COLUMNS)
                        .setInitialRows(INITIAL_ROWS)
                        .start();
            } catch (Exception e) {
                failed(command[0], e);
                return;
            }
            if (disposed.get()) {
                process.destroy();
                return;
            }
            SwingUtilities.invokeLater(() -> attach(process));
        });
    }

    /** Builds the widget around a running process. Event dispatch thread. */
    private void attach(PtyProcess process) {
        if (disposed.get()) {
            process.destroy();
            return;
        }
        connector = new PtyTtyConnector(process, title);
        widget = new SmideTerminalWidget(INITIAL_COLUMNS, INITIAL_ROWS, settings);
        widget.setTtyConnector(connector);
        widget.addListener(new TerminalWidgetListener() {
            @Override
            public void allSessionsClosed(TerminalWidget terminalWidget) {
                // The shell exited (or the tab was closed). Let the tool window drop the tab.
                if (!disposed.get()) {
                    Platform.runLater(() -> {
                        if (!disposed.get()) {
                            onExit.run();
                        }
                    });
                }
            }
        });
        widget.start();
        swingNode.setContent(widget);
        Platform.runLater(() -> {
            if (disposed.get()) {
                return;
            }
            status.setVisible(false);
            swingNode.setVisible(true);
            focus();
        });
    }

    /** Reports a shell that could not be started. Background thread. */
    private void failed(String executable, Exception e) {
        String message = "Could not start " + executable + ": " + e.getMessage();
        Platform.runLater(() -> {
            if (disposed.get()) {
                return;
            }
            status.setText(message + "\nSet the shell under Settings > Tools > Terminal.");
            ide.notifications().error("Terminal", message);
        });
    }

    /** Gives the terminal keyboard focus. JavaFX thread. */
    void focus() {
        if (disposed.get()) {
            return;
        }
        // Both halves are needed: the SwingNode must be the focused FX node for key events to
        // be forwarded, and the Swing panel must be the focus owner inside the embedded frame.
        swingNode.requestFocus();
        SwingUtilities.invokeLater(() -> {
            if (widget != null && !disposed.get()) {
                widget.requestFocusInWindow();
            }
        });
    }

    /** Clears the screen and the scrollback, keeping the current prompt line. */
    void clear() {
        SwingUtilities.invokeLater(() -> {
            if (widget != null && !disposed.get()) {
                widget.getTerminalPanel().clearBuffer();
            }
        });
    }

    /** Re-reads colours and font from the settings provider. Any thread. */
    void restyle() {
        SwingUtilities.invokeLater(() -> {
            if (widget != null && !disposed.get()) {
                widget.restyle();
            }
        });
    }

    /** Kills the shell and releases the widget. Any thread; idempotent. */
    void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (widget != null) {
                widget.close();
                widget = null;
            }
            // close() above asks the connector to close too; this covers a session whose widget
            // never came up, and destroying an already-dead process is harmless.
            if (connector != null) {
                connector.close();
                connector = null;
            }
        });
        Platform.runLater(() -> {
            swingNode.setContent(null);
            node.getChildren().clear();
        });
    }
}
