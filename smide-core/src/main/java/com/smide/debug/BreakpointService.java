package com.smide.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.smide.api.debug.Breakpoint;
import com.smide.api.debug.Breakpoints;
import com.smide.api.workspace.Workspace;
import com.smide.api.workspace.Workspaces;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The breakpoints, kept per workspace in {@code <root>/.smide/breakpoints.json}.
 *
 * <p>Stored with the project rather than with the IDE: breakpoints are about the code,
 * so they should still be there after the workspace is closed and reopened, and they
 * mean nothing in another project.
 */
public final class BreakpointService implements Breakpoints {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "breakpoints.json";

    /** The on-disk shape: paths relative to the workspace root, so a moved checkout still works. */
    private static final class Stored {
        String file;
        int line;
        boolean enabled = true;
        String condition;
    }

    private final Workspaces workspaces;
    private final Map<Path, List<Breakpoint>> byFile = new LinkedHashMap<>();
    private final List<Consumer<Path>> listeners = new CopyOnWriteArrayList<>();

    public BreakpointService(Workspaces workspaces) {
        this.workspaces = workspaces;
        workspaces.addOpenedListener(this::load);
        workspaces.addClosedListener(this::unload);
    }

    @Override
    public synchronized List<Breakpoint> all() {
        List<Breakpoint> out = new ArrayList<>();
        byFile.values().forEach(out::addAll);
        return out;
    }

    @Override
    public synchronized List<Breakpoint> inFile(Path file) {
        return List.copyOf(byFile.getOrDefault(key(file), List.of()));
    }

    @Override
    public synchronized Optional<Breakpoint> at(Path file, int line) {
        return byFile.getOrDefault(key(file), List.of()).stream()
                .filter(b -> b.line() == line).findFirst();
    }

    @Override
    public void toggle(Path file, int line) {
        if (at(file, line).isPresent()) {
            remove(file, line);
        } else {
            add(new Breakpoint(key(file), line));
        }
    }

    @Override
    public void add(Breakpoint breakpoint) {
        Path file = key(breakpoint.file());
        synchronized (this) {
            List<Breakpoint> list = byFile.computeIfAbsent(file, f -> new ArrayList<>());
            list.removeIf(b -> b.line() == breakpoint.line());
            list.add(new Breakpoint(file, breakpoint.line(), breakpoint.enabled(), breakpoint.condition()));
            list.sort((a, b) -> Integer.compare(a.line(), b.line()));
        }
        save(file);
        fire(file);
    }

    @Override
    public void remove(Path file, int line) {
        Path key = key(file);
        synchronized (this) {
            List<Breakpoint> list = byFile.get(key);
            if (list == null || !list.removeIf(b -> b.line() == line)) {
                return;
            }
            if (list.isEmpty()) {
                byFile.remove(key);
            }
        }
        save(key);
        fire(key);
    }

    @Override
    public void update(Breakpoint breakpoint) {
        add(breakpoint);
    }

    @Override
    public void removeAll() {
        List<Path> files;
        synchronized (this) {
            files = new ArrayList<>(byFile.keySet());
            byFile.clear();
        }
        for (Path file : files) {
            save(file);
            fire(file);
        }
    }

    @Override
    public void addListener(Consumer<Path> listener) {
        listeners.add(listener);
    }

    private void fire(Path file) {
        for (Consumer<Path> l : listeners) {
            try {
                l.accept(file);
            } catch (RuntimeException e) {
                System.err.println("smIDE: breakpoint listener failed: " + e);
            }
        }
    }

    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }

    // -------------------------------------------------------------- storage

    private Optional<Workspace> workspaceOf(Path file) {
        return workspaces.containing(file);
    }

    private void load(Workspace workspace) {
        Path file = workspace.configDir().resolve(FILE);
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Stored> stored = GSON.fromJson(reader, new TypeToken<List<Stored>>() {
            }.getType());
            if (stored == null) {
                return;
            }
            synchronized (this) {
                for (Stored s : stored) {
                    Path target = workspace.root().resolve(s.file).normalize();
                    if (!Files.isRegularFile(target)) {
                        continue;
                    }
                    byFile.computeIfAbsent(target, f -> new ArrayList<>())
                            .add(new Breakpoint(target, s.line, s.enabled, s.condition));
                }
            }
            for (Path changed : List.copyOf(byFile.keySet())) {
                fire(changed);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot read " + file + ": " + e);
        }
    }

    private void unload(Workspace workspace) {
        synchronized (this) {
            byFile.keySet().removeIf(f -> f.startsWith(workspace.root()));
        }
    }

    private void save(Path file) {
        Optional<Workspace> workspace = workspaceOf(file);
        if (workspace.isEmpty()) {
            return;
        }
        Workspace ws = workspace.get();
        List<Stored> stored = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Path, List<Breakpoint>> e : byFile.entrySet()) {
                if (!e.getKey().startsWith(ws.root())) {
                    continue;
                }
                for (Breakpoint b : e.getValue()) {
                    Stored s = new Stored();
                    s.file = ws.root().relativize(e.getKey()).toString().replace('\\', '/');
                    s.line = b.line();
                    s.enabled = b.enabled();
                    s.condition = b.condition();
                    stored.add(s);
                }
            }
        }
        Path target = ws.configDir().resolve(FILE);
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
        } catch (IOException e) {
            System.err.println("smIDE: cannot write " + target + ": " + e);
        }
    }
}
