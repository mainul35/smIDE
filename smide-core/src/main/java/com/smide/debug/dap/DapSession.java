package com.smide.debug.dap;

import com.smide.api.Ide;
import com.smide.api.debug.Breakpoint;
import com.smide.api.debug.DebugSession;
import com.smide.api.execution.ConsoleHandle;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A debug session spoken over the Debug Adapter Protocol, whatever the language.
 *
 * <p>The adapter is already listening - a plugin started it with the program, in a console.
 * This connects, attaches, hands over the IDE's breakpoints and keeps them in step, and turns
 * the adapter's events into the state the Debug window reads.
 *
 * <p>The window reads that state on the JavaFX thread and expects an answer at once, while
 * every protocol request is a round trip. So the stack is fetched when the program stops,
 * before anyone is told it stopped, and the requests that cannot be fetched ahead - a
 * variable opened, an expression typed - wait a few seconds at most rather than for ever.
 */
public final class DapSession implements DebugSession {

    /** How long a request made for the window may take. */
    private static final long CALL_MS = 5_000;
    /** How long connecting, attaching and configuring may take. */
    private static final long SETUP_MS = 30_000;
    private static final int MAX_FRAMES = 100;

    private final Ide ide;
    private final String name;
    private final Socket socket;
    /** Where events are handled, in the order they came: the protocol's reader thread must not wait on replies. */
    private final ExecutorService worker;
    private final List<Consumer<DebugSession>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final AtomicBoolean over = new AtomicBoolean();
    private final AtomicBoolean disconnectSent = new AtomicBoolean();
    private volatile boolean closedByAdapter;
    private IDebugProtocolServer server;
    private Capabilities capabilities = new Capabilities();
    private volatile boolean suspended;
    private volatile Integer thread;
    private volatile List<StackFrameInfo> frames = List.of();
    /** The adapter's id for each frame shown, by the frame's index. */
    private volatile int[] frameIds = new int[0];

    private DapSession(Ide ide, String name, Socket socket) {
        this.ide = ide;
        this.name = name;
        this.socket = socket;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smide-dap-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /** Connects to an adapter on a local port and attaches; see {@code DebugAdapters.attach}. */
    public static DapSession connect(Ide ide, String name, int port, Map<String, Object> attachArguments,
                                     Duration timeout, ConsoleHandle console) throws IOException {
        Socket socket = open(port, timeout, console);
        DapSession session = new DapSession(ide, name, socket);
        try {
            session.begin(attachArguments);
        } catch (IOException | RuntimeException e) {
            session.ended();
            throw e;
        }
        return session;
    }

    private static Socket open(int port, Duration timeout, ConsoleHandle console) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        InetAddress local = InetAddress.getByName("127.0.0.1");
        while (System.nanoTime() < deadline) {
            if (console != null && !console.isRunning()) {
                throw new IOException("The program ended before the debugger could connect. What it said is in the Run window.");
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(local, port), 1000);
                return socket;
            } catch (IOException e) {
                last = e;
                socket.close();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while connecting to the debugger", e);
            }
        }
        throw new IOException("The debugger did not open port " + port + " within " + timeout.toSeconds() + " s"
                + (last == null ? "." : ": " + last.getMessage()));
    }

    private void begin(Map<String, Object> attachArguments) throws IOException {
        Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(new Client(),
                socket.getInputStream(), socket.getOutputStream());
        server = launcher.getRemoteProxy();
        Future<Void> listening = launcher.startListening();
        Thread watcher = new Thread(() -> {
            try {
                listening.get();
            } catch (Exception ignored) {
                // Closed either way.
            }
            closedByAdapter = true;
            ended();
        }, "smide-dap-connection-" + name);
        watcher.setDaemon(true);
        watcher.start();

        InitializeRequestArguments init = new InitializeRequestArguments();
        init.setClientID("smide");
        init.setClientName("smIDE");
        init.setAdapterID("smide");
        init.setLocale("en");
        init.setLinesStartAt1(true);
        init.setColumnsStartAt1(true);
        init.setPathFormat("path");
        init.setSupportsVariableType(true);
        Capabilities answered = await(server.initialize(init), SETUP_MS, "start");
        if (answered != null) {
            capabilities = answered;
        }

        /* Adapters answer attach at different moments - debugpy only once configuration is
           done - but all of them say "initialized" when they want breakpoints. Wait for
           that, unless attaching fails first. */
        /* The keys every client sends besides the adapter's own. Not only good manners: an
           empty map is left out of the message altogether, and debugpy refuses an attach
           that has no arguments at all. */
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(attachArguments);
        arguments.putIfAbsent("request", "attach");
        arguments.putIfAbsent("name", name);
        CompletableFuture<Void> attached = server.attach(arguments);
        try {
            CompletableFuture.anyOf(initialized, attached).get(SETUP_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("The debugger would not attach: " + message(e));
        } catch (TimeoutException e) {
            throw new IOException("The debugger did not get ready within " + SETUP_MS / 1000 + " s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while attaching", e);
        }
        await(initialized, SETUP_MS, "get ready");

        Set<Path> files = new LinkedHashSet<>();
        for (Breakpoint b : ide.breakpoints().all()) {
            files.add(b.file());
        }
        for (Path file : files) {
            sendBreakpoints(file);
        }
        if (Boolean.TRUE.equals(capabilities.getSupportsConfigurationDoneRequest())) {
            await(server.configurationDone(new ConfigurationDoneArguments()), SETUP_MS, "start the program");
        }
        attached.whenComplete((ignored, e) -> {
            if (e != null && !over.get()) {
                ide.notifications().error("The debugger stopped", message(e));
                ended();
            }
        });
        // The file's whole list every time, so a breakpoint removed or disabled mid-session is taken back.
        ide.breakpoints().addListener(file -> later(() -> sendBreakpoints(file)));
    }

    private void sendBreakpoints(Path file) {
        if (over.get()) {
            return;
        }
        List<Breakpoint> wanted = ide.breakpoints().inFile(file).stream().filter(Breakpoint::enabled).toList();
        boolean conditions = Boolean.TRUE.equals(capabilities.getSupportsConditionalBreakpoints());
        SourceBreakpoint[] lines = new SourceBreakpoint[wanted.size()];
        for (int i = 0; i < lines.length; i++) {
            Breakpoint b = wanted.get(i);
            lines[i] = new SourceBreakpoint();
            lines[i].setLine(b.line() + 1);
            if (conditions && b.condition() != null) {
                lines[i].setCondition(b.condition());
            }
        }
        Source source = new Source();
        source.setPath(file.toAbsolutePath().toString());
        source.setName(String.valueOf(file.getFileName()));
        SetBreakpointsArguments args = new SetBreakpointsArguments();
        args.setSource(source);
        args.setBreakpoints(lines);
        try {
            await(server.setBreakpoints(args), SETUP_MS, "set the breakpoints in " + file.getFileName());
        } catch (IOException e) {
            System.err.println("smIDE debug: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ events

    private final class Client implements IDebugProtocolClient {

        @Override
        public void initialized() {
            initialized.complete(null);
        }

        @Override
        public void stopped(StoppedEventArguments args) {
            later(() -> onStopped(args.getThreadId()));
        }

        @Override
        public void continued(ContinuedEventArguments args) {
            later(() -> {
                suspended = false;
                forgetStack();
                notifyListeners();
            });
        }

        @Override
        public void exited(ExitedEventArguments args) {
            ended();
        }

        @Override
        public void terminated(TerminatedEventArguments args) {
            ended();
        }
    }

    private void onStopped(Integer threadId) {
        try {
            Integer id = threadId;
            if (id == null) {
                org.eclipse.lsp4j.debug.Thread[] threads = await(server.threads(), CALL_MS, "list the threads").getThreads();
                id = threads == null || threads.length == 0 ? null : threads[0].getId();
            }
            thread = id;
            List<StackFrameInfo> shown = new ArrayList<>();
            int[] ids = new int[0];
            if (id != null) {
                StackTraceArguments args = new StackTraceArguments();
                args.setThreadId(id);
                args.setLevels(MAX_FRAMES);
                StackFrame[] stack = await(server.stackTrace(args), CALL_MS, "read the stack").getStackFrames();
                stack = stack == null ? new StackFrame[0] : stack;
                ids = new int[stack.length];
                for (int i = 0; i < stack.length; i++) {
                    ids[i] = stack[i].getId();
                    shown.add(new StackFrameInfo(stack[i].getName(), pathOf(stack[i].getSource()),
                            Math.max(0, stack[i].getLine() - 1), i));
                }
            }
            frameIds = ids;
            frames = List.copyOf(shown);
        } catch (IOException e) {
            System.err.println("smIDE debug: " + e.getMessage());
            forgetStack();
        }
        suspended = true;
        notifyListeners();
    }

    private static Path pathOf(Source source) {
        if (source == null || source.getPath() == null) {
            return null;
        }
        try {
            return Path.of(source.getPath());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Runs on the worker, in order; nothing once the session is over. */
    private void later(Runnable task) {
        if (over.get()) {
            return;
        }
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // Ended meanwhile.
        }
    }

    private void ended() {
        if (!over.compareAndSet(false, true)) {
            return;
        }
        suspended = false;
        forgetStack();
        initialized.completeExceptionally(new IOException("The debugger disconnected."));
        /* A program that ended leaves the adapter connected - a headless Delve waits for the
           client to say it is done. Saying so makes the adapter close the connection from
           its side; closing it under the reader instead makes lsp4j log a stack trace for a
           perfectly ordinary ending. The socket is closed here only if that did not happen. */
        CompletableFuture<Void> said = closedByAdapter ? CompletableFuture.completedFuture(null) : disconnect(false);
        said.thenRun(() -> CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }));
        worker.shutdown();
        notifyListeners();
    }

    private void forgetStack() {
        frames = List.of();
        frameIds = new int[0];
    }

    private void notifyListeners() {
        ide.window().runLater(() -> listeners.forEach(l -> l.accept(this)));
    }

    // ---------------------------------------------------------- DebugSession

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return !over.get();
    }

    @Override
    public boolean isSuspended() {
        return suspended;
    }

    @Override
    public List<StackFrameInfo> frames() {
        return frames;
    }

    @Override
    public List<VariableInfo> variables(StackFrameInfo frame) {
        Integer id = frameId(frame);
        if (id == null) {
            return List.of();
        }
        try {
            ScopesArguments args = new ScopesArguments();
            args.setFrameId(id);
            Scope[] scopes = await(server.scopes(args), CALL_MS, "read the scopes").getScopes();
            List<VariableInfo> out = new ArrayList<>();
            for (int i = 0; scopes != null && i < scopes.length; i++) {
                Scope scope = scopes[i];
                int reference = scope.getVariablesReference();
                /* The first scope is the frame's own - its locals - which is what the reader
                   came for. The others, globals or registers, are there to be opened. */
                if (i == 0 && !scope.isExpensive()) {
                    out.addAll(variablesOf(reference));
                } else {
                    out.add(new VariableInfo(scope.getName(), "", "", reference > 0, reference));
                }
            }
            return out;
        } catch (IOException e) {
            System.err.println("smIDE debug: " + e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<VariableInfo> children(VariableInfo variable) {
        if (!suspended || !(variable.handle() instanceof Integer reference) || reference <= 0) {
            return List.of();
        }
        try {
            return variablesOf(reference);
        } catch (IOException e) {
            System.err.println("smIDE debug: " + e.getMessage());
            return List.of();
        }
    }

    private List<VariableInfo> variablesOf(int reference) throws IOException {
        VariablesArguments args = new VariablesArguments();
        args.setVariablesReference(reference);
        Variable[] variables = await(server.variables(args), CALL_MS, "read the variables").getVariables();
        List<VariableInfo> out = new ArrayList<>();
        for (Variable v : variables == null ? new Variable[0] : variables) {
            out.add(new VariableInfo(v.getName(), v.getType() == null ? "" : v.getType(),
                    v.getValue() == null ? "" : v.getValue(), v.getVariablesReference() > 0, v.getVariablesReference()));
        }
        return out;
    }

    @Override
    public Evaluation evaluate(StackFrameInfo frame, String expression) {
        Integer id = frameId(frame);
        if (id == null) {
            return Evaluation.failed("The program is not stopped.");
        }
        EvaluateArguments args = new EvaluateArguments();
        args.setExpression(expression);
        args.setFrameId(id);
        args.setContext("watch");
        try {
            EvaluateResponse r = await(server.evaluate(args), CALL_MS, "evaluate " + expression);
            return Evaluation.of(new VariableInfo(expression, r.getType() == null ? "" : r.getType(),
                    r.getResult(), r.getVariablesReference() > 0, r.getVariablesReference()));
        } catch (AdapterError e) {
            // Shown beside the expression, so the adapter's reason alone: "undefined: x".
            return Evaluation.failed(e.reason);
        } catch (IOException e) {
            return Evaluation.failed(e.getMessage());
        }
    }

    private Integer frameId(StackFrameInfo frame) {
        int[] ids = frameIds;
        if (!suspended || frame == null || frame.index() < 0 || frame.index() >= ids.length) {
            return null;
        }
        return ids[frame.index()];
    }

    @Override
    public void resume() {
        step(t -> {
            ContinueArguments args = new ContinueArguments();
            args.setThreadId(t);
            return server.continue_(args);
        });
    }

    @Override
    public void stepOver() {
        step(t -> {
            NextArguments args = new NextArguments();
            args.setThreadId(t);
            return server.next(args);
        });
    }

    @Override
    public void stepInto() {
        step(t -> {
            StepInArguments args = new StepInArguments();
            args.setThreadId(t);
            return server.stepIn(args);
        });
    }

    @Override
    public void stepOut() {
        step(t -> {
            StepOutArguments args = new StepOutArguments();
            args.setThreadId(t);
            return server.stepOut(args);
        });
    }

    private void step(IntFunction<CompletableFuture<?>> request) {
        Integer t = thread;
        if (over.get() || !suspended || t == null) {
            return;
        }
        later(() -> {
            suspended = false;
            forgetStack();
            notifyListeners();
            request.apply(t).whenComplete((ignored, e) -> {
                if (e != null) {
                    System.err.println("smIDE debug: " + message(e));
                }
            });
        });
    }

    @Override
    public void stop() {
        if (over.get()) {
            return;
        }
        // The adapter ends the program and closes the connection; if it does neither, give up on it.
        disconnect(true).whenComplete((ignored, e) -> ended());
    }

    /** Tells the adapter the client is done, once; completes when it answers, or after a few seconds. */
    private CompletableFuture<Void> disconnect(boolean terminateProgram) {
        if (server == null || closedByAdapter || !disconnectSent.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        DisconnectArguments args = new DisconnectArguments();
        if (terminateProgram) {
            args.setTerminateDebuggee(true);
        }
        try {
            return server.disconnect(args).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> null);
        } catch (RuntimeException e) {
            // The connection went while sending.
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void addListener(Consumer<DebugSession> listener) {
        listeners.add(listener);
    }

    // ----------------------------------------------------------------- helpers

    private static <T> T await(CompletableFuture<T> future, long millis, String what) throws IOException {
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("The debugger did not " + what + " within " + millis / 1000 + " s.");
        } catch (ExecutionException e) {
            throw new AdapterError("Could not " + what + ": " + message(e), message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** A request the adapter answered with an error, and the reason it gave. */
    private static final class AdapterError extends IOException {
        final String reason;

        AdapterError(String message, String reason) {
            super(message);
            this.reason = reason;
        }
    }

    /** What went wrong, in the adapter's words when it gave any. */
    static String message(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof ResponseErrorException r && r.getResponseError() != null) {
            return r.getResponseError().getMessage();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
