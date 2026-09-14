package com.smide.execution;

import com.smide.api.Ide;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.execution.RunMarker;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.editor.CodeEditor;
import com.smide.ui.Icons;
import javafx.animation.PauseTransition;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The run icons in the editor's gutter, beside the lines a run can start from.
 *
 * <p>Every run configuration type is asked which lines of an open file it can run - a main
 * method, a main function, a script's {@code __main__} block - when the file opens, again a
 * moment after it is edited, and again when its project finishes importing, since a Java
 * main class is only known once it has. The asking happens off the UI thread.
 *
 * <p>An icon runs the configuration detection makes for the same thing, or the saved one of
 * that name when there is one, so arguments and environment given to it still apply.
 */
public final class RunMarkers {

    private static final Duration AFTER_EDIT = Duration.millis(700);
    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smide-run-markers");
        t.setDaemon(true);
        return t;
    });

    private final Ide ide;
    private final ExtensionRegistry registry;
    /** The open editors with run icons. JavaFX thread only. */
    private final Map<CodeEditor, Scan> scans = new HashMap<>();

    public RunMarkers(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
        ide.editors().addClosedListener(editor -> {
            Scan scan = scans.remove(editor);
            if (scan != null) {
                scan.delay.stop();
            }
        });
        ide.events().subscribe(Events.ProjectImported.class, event -> ide.window().runLater(() -> {
            for (Scan scan : List.copyOf(scans.values())) {
                if (scan.editor.workspace() == event.workspace()) {
                    scan.now();
                }
            }
        }));
    }

    /** Gives an editor its run icons and keeps them current. Files outside a project, and library sources, get none. */
    public void attach(CodeEditor editor) {
        if (editor.workspace() == null || !editor.area().isEditable() || scans.containsKey(editor)) {
            return;
        }
        Scan scan = new Scan(editor);
        scans.put(editor, scan);
        editor.addTextListener(text -> scan.later());
        scan.now();
    }

    /** What clicking an icon offers: run, and debug where it can be, each thing that starts on its line. */
    ContextMenu menu(List<RunMarker> markers) {
        ContextMenu menu = new ContextMenu();
        RunConfiguration first = null;
        for (RunMarker marker : markers) {
            RunConfiguration configuration;
            try {
                configuration = existing(marker.configuration().get());
            } catch (RuntimeException e) {
                MenuItem failed = new MenuItem("Cannot run " + marker.name() + ": " + e.getMessage());
                failed.setDisable(true);
                menu.getItems().add(failed);
                continue;
            }
            if (first == null) {
                first = configuration;
            }
            MenuItem run = new MenuItem("Run '" + marker.name() + "'", Icons.of("fth-play", 13));
            run.setOnAction(e -> ide.execution().run(configuration, ExecutionMode.RUN));
            menu.getItems().add(run);
            if (configuration.type().supportsDebug()) {
                MenuItem debug = new MenuItem("Debug '" + marker.name() + "'", Icons.of("mdi2b-bug", 13));
                debug.setOnAction(e -> ide.execution().run(configuration, ExecutionMode.DEBUG));
                menu.getItems().add(debug);
            }
        }
        if (first != null) {
            RunConfiguration chosen = first;
            MenuItem edit = new MenuItem("Edit Configurations...");
            edit.setOnAction(e -> {
                ide.execution().selectConfiguration(chosen);
                ide.actions().invoke("run.editConfigurations");
            });
            menu.getItems().addAll(new SeparatorMenuItem(), edit);
        }
        return menu;
    }

    /** The saved or detected configuration of the same type and name, so its settings apply; else the one made. */
    private RunConfiguration existing(RunConfiguration made) {
        try {
            for (RunConfiguration c : ide.execution().configurations(made.workspace())) {
                if (c.type().id().equals(made.type().id()) && c.name().equals(made.name())) {
                    return c;
                }
            }
        } catch (RuntimeException e) {
            // The one made will do.
        }
        return made;
    }

    /** One editor's icons: worked out now, or a moment after the last edit. */
    private final class Scan {
        final CodeEditor editor;
        final PauseTransition delay = new PauseTransition(AFTER_EDIT);
        int generation;

        Scan(CodeEditor editor) {
            this.editor = editor;
            delay.setOnFinished(e -> now());
        }

        void later() {
            delay.playFromStart();
        }

        void now() {
            delay.stop();
            int asked = ++generation;
            Workspace workspace = editor.workspace();
            Path file = editor.path();
            String text = editor.text();
            List<RunConfigurationType> types = List.copyOf(registry.runTypes());
            SCANNER.execute(() -> {
                Map<Integer, List<RunMarker>> found = new TreeMap<>();
                for (RunConfigurationType type : types) {
                    try {
                        for (RunMarker marker : type.markers(workspace, file, text)) {
                            found.computeIfAbsent(marker.line(), line -> new ArrayList<>()).add(marker);
                        }
                    } catch (RuntimeException | LinkageError e) {
                        System.err.println("smIDE: run icons from " + type.id() + " failed: " + e);
                    }
                }
                ide.window().runLater(() -> {
                    // Only the newest answer, and only for an editor still open.
                    if (asked == generation && scans.get(editor) == this && !editor.isDisposed()) {
                        editor.setRunMarkers(found, RunMarkers.this::menu);
                    }
                });
            });
        }
    }
}
