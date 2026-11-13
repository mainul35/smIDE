package com.smide.plugins.notebook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One kernel, as a process and two pipes.
 *
 * <p>What is actually started is the interpreter running {@link KernelHelper the helper
 * script}, which starts the kernel itself and speaks ZMQ to it; the reasons are written
 * down in the helper. From here it is a process that takes one JSON command per line and
 * writes one JSON event per line, and the whole of the protocol is {@link KernelEvent}.
 *
 * <p>Never a {@code Scripts\jupyter.exe} or any other launcher pip installed, always the
 * interpreter by its own path. The launchers are unsigned executables and on Windows with
 * Smart App Control on they do not start at all - a failure that arrives as a bare
 * "cannot run program" with nothing to act on. The interpreter is signed, and is also the
 * only way to be sure which Python a notebook is running in.
 *
 * <p>Events arrive on the reader thread, not the UI thread: whoever is listening hands
 * them on. Nothing here knows about JavaFX.
 */
public final class Kernel {

    /** Where a kernel is in its life. */
    public enum State {
        /** Starting, or restarting; no cell can run yet. */
        STARTING,
        /** Up, and nothing running. */
        IDLE,
        /** Running something. */
        BUSY,
        /** It never started, or it died; {@link Kernel#failure()} says why. */
        FAILED,
        /** Shut down on purpose. */
        STOPPED
    }

    private final Path interpreter;
    private final Path helper;
    private final Path workingDirectory;
    private final Consumer<KernelEvent> listener;
    private final Consumer<String> log;
    private final AtomicInteger requests = new AtomicInteger();

    private Process process;
    private BufferedWriter commands;
    private volatile State state = State.STARTING;
    private volatile String failure;

    /**
     * @param interpreter      the project's Python
     * @param helper           the helper script, already on disk
     * @param workingDirectory where a cell's relative paths resolve: the notebook's own
     *                         folder, as Jupyter does, so that {@code open("data.csv")}
     *                         means the file beside the notebook
     * @param listener         every event, on the reader thread
     * @param log              lines the helper wrote to standard error, and notes from here
     */
    public Kernel(Path interpreter, Path helper, Path workingDirectory,
                  Consumer<KernelEvent> listener, Consumer<String> log) {
        this.interpreter = interpreter;
        this.helper = helper;
        this.workingDirectory = workingDirectory;
        this.listener = listener;
        this.log = log == null ? line -> {
        } : log;
    }

    /** The command that is run, which is also what the log and the tests check. */
    public List<String> command() {
        return List.of(interpreter.toString(), "-u", helper.toString());
    }

    public State state() {
        return state;
    }

    public String failure() {
        return failure;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Starts the helper and begins reading it. Returns as soon as the process is up. */
    public synchronized void start() throws IOException {
        if (process != null) {
            throw new IllegalStateException("already started");
        }
        ProcessBuilder builder = new ProcessBuilder(command());
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            builder.directory(workingDirectory.toFile());
        }
        // Unbuffered on the Python side too, or a kernel's output would arrive in 8K lumps.
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        log.accept("Starting kernel: " + String.join(" ", command()));
        process = builder.start();
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                StandardCharsets.UTF_8));
        pump("smide-kernel-out", process.getInputStream(), this::onLine);
        pump("smide-kernel-err", process.getErrorStream(), log);
        process.onExit().thenAccept(exited -> {
            if (state != State.STOPPED) {
                state = State.FAILED;
                if (failure == null) {
                    failure = "the kernel process exited with code " + exited.exitValue();
                }
                listener.accept(new KernelEvent(KernelEvent.FAILED, null, null,
                        new com.google.gson.JsonObject(), failure, null, null, null));
            }
        });
    }

    /**
     * Asks for a cell to be run, and returns the id its events will carry.
     *
     * <p>The id is this plugin's own and not the kernel's message id, so that the editor
     * can label a run before the kernel has seen it. The helper keeps the mapping.
     */
    public String execute(String code) throws IOException {
        String id = "run-" + requests.incrementAndGet();
        com.google.gson.JsonObject command = new com.google.gson.JsonObject();
        command.addProperty("type", "execute");
        command.addProperty("id", id);
        command.addProperty("code", code == null ? "" : code);
        send(command);
        state = State.BUSY;
        return id;
    }

    public void interrupt() throws IOException {
        send(simple("interrupt"));
    }

    public void restart() throws IOException {
        state = State.STARTING;
        send(simple("restart"));
    }

    /**
     * Shuts the kernel down and stops reading it.
     *
     * <p>Asked for first and then insisted on: the helper is given a moment to take the
     * kernel down cleanly, which is what frees its sockets and its connection file, and is
     * killed if it does not. Closing an editor must not wait on a kernel that has hung.
     */
    public synchronized void shutdown() {
        if (process == null || state == State.STOPPED) {
            state = State.STOPPED;
            return;
        }
        state = State.STOPPED;
        try {
            send(simple("shutdown"));
        } catch (IOException e) {
            // Already gone, or the pipe is closed; the kill below is the answer either way.
        }
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void onLine(String line) {
        KernelEvent event = KernelEvent.parse(line);
        if (!event.isUnderstood()) {
            // Something in the project printed on import. Worth seeing, not worth dying for.
            log.accept("kernel said: " + line);
            return;
        }
        switch (event.type()) {
            case KernelEvent.READY, KernelEvent.RESTARTED -> state = State.IDLE;
            case KernelEvent.MISSING -> {
                state = State.FAILED;
                failure = "this project's interpreter has no " + event.module();
            }
            case KernelEvent.FAILED -> {
                state = State.FAILED;
                failure = event.message();
            }
            case KernelEvent.REPLY -> {
                if (state == State.BUSY) {
                    state = State.IDLE;
                }
            }
            default -> {
                // A published message; the editor decides what it means.
            }
        }
        listener.accept(event);
    }

    private static com.google.gson.JsonObject simple(String type) {
        com.google.gson.JsonObject command = new com.google.gson.JsonObject();
        command.addProperty("type", type);
        return command;
    }

    private synchronized void send(com.google.gson.JsonObject command) throws IOException {
        if (commands == null) {
            throw new IOException("the kernel has not been started");
        }
        commands.write(command.toString());
        commands.write("\n");
        commands.flush();
    }

    private void pump(String name, java.io.InputStream stream, Consumer<String> onLine) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException e) {
                // The process ended; onExit has the story.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }
}
