package com.smide.problems;

import com.smide.api.problems.Diagnostic;
import com.smide.api.problems.Problems;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ProblemsService implements Problems {

    private final Map<String, Map<Path, List<Diagnostic>>> bySource = new HashMap<>();
    private final List<Consumer<Path>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> anyChangeListeners = new CopyOnWriteArrayList<>();

    @Override
    public void set(String source, Path file, List<Diagnostic> diagnostics) {
        Path key = file.toAbsolutePath().normalize();
        List<Diagnostic> copy = List.copyOf(diagnostics);
        Platform.runLater(() -> {
            Map<Path, List<Diagnostic>> files = bySource.computeIfAbsent(source, s -> new HashMap<>());
            if (copy.isEmpty()) {
                files.remove(key);
            } else {
                files.put(key, copy);
            }
            fire(key);
        });
    }

    @Override
    public void clear(String source) {
        Platform.runLater(() -> {
            Map<Path, List<Diagnostic>> removed = bySource.remove(source);
            if (removed != null) {
                removed.keySet().forEach(this::fire);
            }
        });
    }

    private void fire(Path file) {
        for (Consumer<Path> l : listeners) {
            try {
                l.accept(file);
            } catch (RuntimeException e) {
                System.err.println("smIDE: problems listener failed: " + e);
            }
        }
        anyChangeListeners.forEach(Runnable::run);
    }

    @Override
    public List<Diagnostic> forFile(Path file) {
        Path key = file.toAbsolutePath().normalize();
        List<Diagnostic> out = new ArrayList<>();
        for (Map<Path, List<Diagnostic>> files : bySource.values()) {
            List<Diagnostic> list = files.get(key);
            if (list != null) {
                out.addAll(list);
            }
        }
        return out;
    }

    @Override
    public List<Diagnostic> all() {
        List<Diagnostic> out = new ArrayList<>();
        for (Map<Path, List<Diagnostic>> files : bySource.values()) {
            files.values().forEach(out::addAll);
        }
        return out;
    }

    @Override
    public void addListener(Consumer<Path> fileChanged) {
        listeners.add(fileChanged);
    }

    public void addAnyChangeListener(Runnable listener) {
        anyChangeListeners.add(listener);
    }

    public int errorCount() {
        return (int) all().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
    }

    public int warningCount() {
        return (int) all().stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
    }
}
