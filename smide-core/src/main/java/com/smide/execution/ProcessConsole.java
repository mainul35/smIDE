package com.smide.execution;

import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.Executables;
import com.smide.api.execution.ProcessSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One process, its console, and the thread pumping output between them. */
public final class ProcessConsole implements ConsoleHandle {

    private final ProcessSpec spec;
    private final ConsoleView view;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private Process process;
    private volatile boolean stopping;

    public ProcessConsole(ProcessSpec spec, ConsoleView view) {
        this.spec = spec;
        this.view = view;
        view.setOnInput(this::writeInput);
    }

    public ProcessSpec spec() {
        return spec;
    }

    public ConsoleView view() {
        return view;
    }

    public void start() {
        view.appendSystem(String.join(" ", spec.command()));
        if (spec.workingDir() != null) {
            view.appendSystem("in " + spec.workingDir());
        }
        // "mvn" on Windows is a shell script that CreateProcess cannot start; mvn.cmd beside it
        // is the one it can. Every process the IDE runs goes through here, so it is asked for by
        // name once, here, and started by the path of the file that actually exists.
        ProcessBuilder pb = new ProcessBuilder(Executables.runnable(spec.command()))
                .redirectErrorStream(true);
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        pb.environment().putAll(spec.environment());
        try {
            process = pb.start();
        } catch (IOException e) {
            view.appendError("Cannot start: " + e.getMessage());
            exit.complete(-1);
            view.setInputEnabled(false);
            return;
        }
        Thread pump = new Thread(this::pump, "smide-console-" + spec.title());
        pump.setDaemon(true);
        pump.start();
    }

    private void pump() {
        Charset charset = StandardCharsets.UTF_8;
        byte[] buf = new byte[8192];
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = new String(buf, 0, n, charset);
                synchronized (output) {
                    output.append(chunk);
                    if (output.length() > 4_000_000) {
                        output.delete(0, output.length() - 4_000_000);
                    }
                }
                view.append(chunk);
                for (Consumer<String> l : listeners) {
                    try {
                        l.accept(chunk);
                    } catch (RuntimeException ignored) {
                        // A listener must not stop the pump.
                    }
                }
            }
        } catch (IOException e) {
            if (!stopping) {
                view.appendError("Output stream closed: " + e.getMessage());
            }
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        view.appendSystem("\nProcess finished with exit code " + code);
        view.setInputEnabled(false);
        exit.complete(code);
    }

    @Override
    public String title() {
        return spec.title();
    }

    @Override
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    @Override
    public void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        stopping = true;
        view.appendSystem("Stopping...");
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                process.destroyForcibly();
            }
        }, "smide-console-stop");
        killer.setDaemon(true);
        killer.start();
    }

    @Override
    public CompletableFuture<Integer> exitCode() {
        return exit;
    }

    @Override
    public void writeInput(String text) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            OutputStream out = process.getOutputStream();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            view.appendError("Cannot write to process: " + e.getMessage());
        }
    }

    @Override
    public void println(String line) {
        view.appendSystem(line);
    }

    @Override
    public void addOutputListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    @Override
    public String output() {
        synchronized (output) {
            return output.toString();
        }
    }
}
