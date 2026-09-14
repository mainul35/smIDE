package com.smide.execution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.smide.api.Ide;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.Execution;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.ui.StatusBar;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Starts processes into the Run tool window and keeps run configurations, which live
 * in {@code <root>/.smide/run-configurations.json} per workspace.
 */
public final class ExecutionService implements Execution {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SELECTED_KEY = "run.selected";

    private static final class Stored {
        String type;
        String name;
        Map<String, String> values = new LinkedHashMap<>();
    }

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final List<ProcessConsole> consoles = new ArrayList<>();
    private final Map<Workspace, List<RunConfiguration>> saved = new HashMap<>();
    private final List<Consumer<ProcessConsole>> startListeners = new ArrayList<>();
    private final List<Runnable> configListeners = new ArrayList<>();
    private RunConfiguration selected;
    private RunConfiguration lastRun;
    private ExecutionMode lastMode = ExecutionMode.RUN;

    /** What detection last found in each workspace; a workspace absent has not been looked at yet. */
    private final Map<Workspace, List<RunConfiguration>> detected = new java.util.concurrent.ConcurrentHashMap<>();
    /** Workspaces being looked at right now, off the UI thread. */
    private final java.util.Set<Workspace> detecting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Bumped whenever a workspace changes, so a pass that started before the change is done again. */
    private final Map<Workspace, Integer> generations = new java.util.concurrent.ConcurrentHashMap<>();

    public ExecutionService(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
        // What a project holds decides what is detected, so a change to it is a reason to look again.
        ide.events().subscribe(com.smide.api.util.Events.FilesChanged.class, e -> redetect(e.workspace()));
        ide.events().subscribe(com.smide.api.util.Events.WorkspaceClosed.class, e -> forget(e.workspace()));
    }

    public void addStartListener(Consumer<ProcessConsole> listener) {
        startListeners.add(listener);
    }

    public void addConfigurationsListener(Runnable listener) {
        configListeners.add(listener);
    }

    private void fireConfigurations() {
        configListeners.forEach(Runnable::run);
    }

    /**
     * The set of configurations may have changed for a reason outside this service - a
     * project import finished, so detection can now see main classes and tests.
     */
    public void configurationsChanged() {
        // An import finishing is what lets detection see main classes and tests: look again.
        for (Workspace workspace : List.copyOf(detected.keySet())) {
            redetect(workspace);
        }
        fireConfigurations();
    }

    @Override
    public ConsoleHandle run(ProcessSpec spec) {
        ConsoleView view = new ConsoleView();
        ProcessConsole console = new ProcessConsole(spec, view);
        view.setOnFileRef(ref -> openFileRef(ref));
        consoles.add(console);
        console.exitCode().thenRun(() -> consoles.remove(console));
        startListeners.forEach(l -> l.accept(console));
        /* Opened from here rather than by the tool window itself: a tool window that has
           never been shown has no handle to show itself with, so the first run of a
           session produced output nobody could see. */
        ide.toolWindows().show(RunToolWindow.ID);
        console.start();
        return console;
    }

    private void openFileRef(ConsoleView.FileRef ref) {
        ide.window().runInBackground(() -> {
            Path found = resolve(ref.fileName());
            if (found != null) {
                ide.window().runLater(() -> ide.editors().open(found, ref.line() - 1, Math.max(0, ref.column() - 1)));
            } else {
                ide.statusBar().message("Not found in any workspace: " + ref.fileName());
            }
        });
    }

    private Path resolve(String fileName) {
        try {
            Path direct = Path.of(fileName);
            if (direct.isAbsolute() && Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (RuntimeException ignored) {
            // Not a path.
        }
        String name = Path.of(fileName).getFileName().toString();
        for (Workspace w : ide.workspaces().all()) {
            Path relative = w.root().resolve(fileName);
            if (Files.isRegularFile(relative)) {
                return relative;
            }
            try (Stream<Path> walk = Files.walk(w.root(), 12)) {
                Optional<Path> hit = walk
                        .filter(p -> !p.toString().contains("target") && !p.toString().contains("node_modules"))
                        .filter(p -> p.getFileName().toString().equals(name))
                        .filter(Files::isRegularFile)
                        .findFirst();
                if (hit.isPresent()) {
                    return hit.get();
                }
            } catch (IOException | RuntimeException ignored) {
                // Try the next workspace.
            }
        }
        return null;
    }

    /**
     * Runs a configuration.
     *
     * <p>Preparation is not instant - an application configuration compiles the module
     * and resolves its classpath with Maven first - so it happens on a background thread.
     * Doing it inline froze the window for the length of a build, which looked exactly
     * like the Run button doing nothing. The console appears when the process starts;
     * until then the status bar carries the progress.
     *
     * @return null, because the process does not exist yet
     */
    @Override
    public ConsoleHandle run(RunConfiguration configuration, ExecutionMode mode) {
        selectConfiguration(configuration);
        lastRun = configuration;
        lastMode = mode;
        StatusBar.Progress progress = ide.statusBar().progress(
                (mode == ExecutionMode.DEBUG ? "Debugging " : "Running ") + configuration.name(), false);
        ide.window().runInBackground(() -> {
            ProcessSpec spec;
            try {
                spec = configuration.prepare(ide, mode);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                // A missing tool comes with the button that installs it.
                com.smide.api.ui.Notifications.NotificationAction[] actions =
                        e instanceof com.smide.api.execution.CannotRunException cannot
                                ? cannot.actions().toArray(new com.smide.api.ui.Notifications.NotificationAction[0])
                                : new com.smide.api.ui.Notifications.NotificationAction[0];
                ide.notifications().error((mode == ExecutionMode.DEBUG ? "Cannot debug " : "Cannot run ")
                        + configuration.name(), message, actions);
                progress.done();
                return;
            }
            progress.done();
            ide.window().runLater(() -> {
                ConsoleHandle console = run(spec);
                if (mode == ExecutionMode.DEBUG) {
                    attachDebugger(configuration, console);
                }
            });
        });
        return null;
    }

    /** Set by the window so a session can be shown; null until the core is built. */
    private java.util.function.Consumer<com.smide.api.debug.DebugSession> debugSessionSink;

    public void setDebugSessionSink(java.util.function.Consumer<com.smide.api.debug.DebugSession> sink) {
        this.debugSessionSink = sink;
    }

    /**
     * Connects to the process that was just started with a debug agent.
     *
     * <p>The agent is told to wait for a debugger, so attaching is what lets the program
     * begin; if no plugin can debug this kind of configuration the user is told, because
     * otherwise the program would sit there for ever with no explanation.
     */
    private void attachDebugger(RunConfiguration configuration, ConsoleHandle console) {
        com.smide.api.debug.Debugger debugger = registry.debuggers().stream()
                .filter(d -> d.supports(configuration))
                .findFirst()
                .orElse(null);
        if (debugger == null) {
            ide.notifications().warn("No debugger",
                    "Nothing can debug a " + configuration.type().displayName() + " configuration.");
            return;
        }
        int port = debugPortOf(configuration);
        StatusBar.Progress progress = ide.statusBar().progress("Attaching the debugger", false);
        ide.window().runInBackground(() -> {
            try {
                com.smide.api.debug.DebugSession session = debugger.attach(ide, configuration, port, console);
                ide.window().runLater(() -> {
                    if (debugSessionSink != null) {
                        debugSessionSink.accept(session);
                    }
                    ide.statusBar().message("Debugger attached on port " + port);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                ide.notifications().error("Cannot attach the debugger", message);
            } finally {
                progress.done();
            }
        });
    }

    /** The port the configuration's agent listens on; 5005 unless it says otherwise. */
    public static int debugPortOf(RunConfiguration configuration) {
        try {
            String value = configuration.toMap().get("debugPort");
            return value == null || value.isBlank() ? 5005 : Integer.parseInt(value.strip());
        } catch (RuntimeException e) {
            return 5005;
        }
    }

    public Optional<RunConfiguration> lastRun() {
        return Optional.ofNullable(lastRun);
    }

    public ExecutionMode lastMode() {
        return lastMode;
    }

    @Override
    public List<ConsoleHandle> running() {
        return consoles.stream().filter(ConsoleHandle::isRunning).map(c -> (ConsoleHandle) c).toList();
    }

    public void stopAll() {
        consoles.forEach(ProcessConsole::stop);
    }

    // --------------------------------------------------------- configurations

    /**
     * The saved configurations, and the ones detection has found so far.
     *
     * <p>Detection walks the project - every language looking for its programs and tests -
     * and it used to do so right here, on every call. This is called from the UI thread by
     * the run toolbar, the run list and Search Everywhere, so each of them froze the window
     * for as long as the walk took: most of a second in a Go module, several in a folder
     * like site-packages. Now the walk happens off the UI thread, once, and again only when
     * the project changes; what it finds arrives through the configurations listeners.
     */
    @Override
    public List<RunConfiguration> configurations(Workspace workspace) {
        List<RunConfiguration> out = new ArrayList<>(load(workspace));
        List<RunConfiguration> found = detected.get(workspace);
        if (found == null) {
            startDetection(workspace);
            return out;
        }
        for (RunConfiguration candidate : found) {
            boolean dup = out.stream().anyMatch(c -> c.name().equals(candidate.name()) && c.type() == candidate.type());
            if (!dup) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** Looks at a workspace again, off the UI thread; what was found stays until the new answer arrives. */
    public void redetect(Workspace workspace) {
        if (workspace == null) {
            return;
        }
        generations.merge(workspace, 1, Integer::sum);
        startDetection(workspace);
    }

    private void forget(Workspace workspace) {
        detected.remove(workspace);
        generations.remove(workspace);
        saved.remove(workspace);
    }

    private void startDetection(Workspace workspace) {
        if (!detecting.add(workspace)) {
            // Already looking; the generation bump makes that pass run once more when it ends.
            return;
        }
        int generation = generations.getOrDefault(workspace, 0);
        List<RunConfigurationType> types = List.copyOf(registry.runTypes());
        ide.window().runInBackground(() -> {
            List<RunConfiguration> found = new ArrayList<>();
            for (RunConfigurationType type : types) {
                try {
                    found.addAll(type.detect(workspace));
                } catch (RuntimeException e) {
                    System.err.println("smIDE: run configuration detection failed for " + type.id() + ": " + e);
                }
            }
            ide.window().runLater(() -> {
                detecting.remove(workspace);
                if (generations.getOrDefault(workspace, 0) != generation) {
                    startDetection(workspace);
                    return;
                }
                detected.put(workspace, found);
                fireConfigurations();
            });
        });
    }

    private List<RunConfiguration> load(Workspace workspace) {
        List<RunConfiguration> cached = saved.get(workspace);
        if (cached != null) {
            return cached;
        }
        List<RunConfiguration> list = new ArrayList<>();
        Path file = workspace.configDir().resolve("run-configurations.json");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                List<Stored> stored = GSON.fromJson(reader, new TypeToken<List<Stored>>() {
                }.getType());
                if (stored != null) {
                    for (Stored s : stored) {
                        registry.runTypes().stream().filter(t -> t.id().equals(s.type)).findFirst().ifPresent(type -> {
                            RunConfiguration c = type.create(workspace);
                            c.setName(s.name);
                            c.fromMap(s.values == null ? Map.of() : s.values);
                            list.add(c);
                        });
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("smIDE: cannot read " + file + ": " + e);
            }
        }
        saved.put(workspace, list);
        return list;
    }

    private void persist(Workspace workspace) {
        List<RunConfiguration> list = load(workspace);
        List<Stored> stored = new ArrayList<>();
        for (RunConfiguration c : list) {
            Stored s = new Stored();
            s.type = c.type().id();
            s.name = c.name();
            s.values = new LinkedHashMap<>(c.toMap());
            stored.add(s);
        }
        Path file = workspace.configDir().resolve("run-configurations.json");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
        } catch (IOException e) {
            ide.notifications().error("Cannot save run configurations", e.getMessage());
        }
        fireConfigurations();
    }

    @Override
    public Optional<RunConfiguration> selectedConfiguration() {
        if (selected != null) {
            return Optional.of(selected);
        }
        Optional<Workspace> ws = ide.workspaces().active();
        if (ws.isEmpty()) {
            return Optional.empty();
        }
        String name = ws.get().settings().get(SELECTED_KEY, null);
        List<RunConfiguration> all = configurations(ws.get());
        if (name != null) {
            for (RunConfiguration c : all) {
                if (c.name().equals(name)) {
                    selected = c;
                    return Optional.of(c);
                }
            }
        }
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    @Override
    public void selectConfiguration(RunConfiguration configuration) {
        selected = configuration;
        if (configuration != null) {
            configuration.workspace().settings().set(SELECTED_KEY, configuration.name());
        }
        fireConfigurations();
    }

    @Override
    public void saveConfiguration(RunConfiguration configuration) {
        List<RunConfiguration> list = load(configuration.workspace());
        list.removeIf(c -> c.name().equals(configuration.name()) && c.type() == configuration.type());
        list.add(configuration);
        persist(configuration.workspace());
    }

    @Override
    public void deleteConfiguration(RunConfiguration configuration) {
        List<RunConfiguration> list = load(configuration.workspace());
        list.remove(configuration);
        if (selected == configuration) {
            selected = null;
        }
        persist(configuration.workspace());
    }

    @Override
    public List<RunConfigurationType> configurationTypes() {
        return List.copyOf(registry.runTypes());
    }

    /** Forget the selection when its workspace closes. */
    public void workspaceClosed(Workspace workspace) {
        saved.remove(workspace);
        if (selected != null && selected.workspace() == workspace) {
            selected = null;
        }
        fireConfigurations();
    }
}
