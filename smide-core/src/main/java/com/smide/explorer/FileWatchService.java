package com.smide.explorer;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Watches the directories the explorer has listed and reports, on the JavaFX thread and
 * with a short debounce, which of them changed. A build writing a thousand class files
 * arrives as one refresh of each directory, not a thousand.
 */
public final class FileWatchService {

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Map<Path, WatchKey> byPath = new HashMap<>();
    private final Consumer<Set<Path>> onChanged;
    private final Set<Path> pending = new HashSet<>();
    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean flushScheduled;

    public FileWatchService(Consumer<Set<Path>> onChanged) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.onChanged = onChanged;
    }

    public void start() {
        // Real, not virtual: WatchService.take() blocks in the operating system for the life of
        // the session, which would pin a carrier thread and never give it back. See arc42 §8.2.
        thread = new Thread(this::loop, "smide-file-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void watch(Path dir) {
        if (byPath.containsKey(dir)) {
            return;
        }
        try {
            WatchKey key = dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            keys.put(key, dir);
            byPath.put(dir, key);
        } catch (IOException | RuntimeException e) {
            // Unwatchable (network share, permissions): the manual refresh still works.
        }
    }

    public synchronized void unwatchUnder(Path root) {
        for (Map.Entry<Path, WatchKey> e : Set.copyOf(byPath.entrySet())) {
            if (e.getKey().startsWith(root)) {
                e.getValue().cancel();
                keys.remove(e.getValue());
                byPath.remove(e.getKey());
            }
        }
    }

    private void loop() {
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Path dir;
            synchronized (this) {
                dir = keys.get(key);
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                if (dir != null) {
                    synchronized (lock) {
                        pending.add(dir);
                    }
                }
            }
            if (!key.reset()) {
                synchronized (this) {
                    keys.remove(key);
                    if (dir != null) {
                        byPath.remove(dir);
                    }
                }
            }
            scheduleFlush();
        }
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(350);
            } catch (InterruptedException ignored) {
                // Flush anyway.
            }
            Set<Path> batch;
            synchronized (lock) {
                batch = new HashSet<>(pending);
                pending.clear();
            }
            flushScheduled = false;
            if (!batch.isEmpty()) {
                Platform.runLater(() -> onChanged.accept(batch));
            }
        }, "smide-file-watcher-flush");
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        try {
            watcher.close();
        } catch (IOException ignored) {
            // Shutting down.
        }
    }
}
