package com.smide.plugins.notebook;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.workspace.Workspace;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * pyright, for one notebook, shown the cells as one program.
 *
 * <p>A plugin cannot use the IDE's own language server: the core owns those processes, it
 * starts one per language per workspace from a file's extension, and a {@code .ipynb} is
 * not Python so none is started for it. Nor is there an API for a plugin to ask one a
 * question - deliberately, since nothing else needs it. What there is, is the Python
 * plugin's launcher, reachable through {@link LanguageSupport#languageServer()}: it knows
 * where pyright is, whether it is installed, how to install it and what environment it
 * needs. So that launcher says what to run, and this starts a pyright of its own and
 * speaks to it.
 *
 * <p>One extra pyright process per open notebook is the cost. It is worth paying because
 * the alternatives are worse: no completion in a notebook at all, or a word-list that
 * cannot see an import.
 *
 * <p>What pyright is shown is a Python file that does not exist - the notebook's path with
 * {@code .py} after it, opened through the protocol and never written. {@link CellContext}
 * decides what goes in it and maps the answers back; this class is the process, and the
 * mapping is tested without one.
 */
final class Pyright {

    /** How long a completion is waited for before the reader is left alone. */
    private static final long COMPLETION_TIMEOUT_MS = 2_500;

    private final Ide ide;
    private final Workspace workspace;
    private final Path notebook;
    /** Called with the diagnostics for one cell, on whatever thread pyright answered on. */
    private final Consumer<CellDiagnostics> onDiagnostics;
    private final ExecutorService threads = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smide-notebook-pyright");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger version = new AtomicInteger();

    private Process process;
    private LanguageServer server;
    private volatile boolean opened;
    private volatile boolean broken;
    /** The context the last text sent to pyright was built from, to map answers back. */
    private volatile CellContext sent;

    /** Diagnostics for one cell of the notebook, already mapped to its own lines. */
    record CellDiagnostics(int cellIndex, List<com.smide.api.problems.Diagnostic> diagnostics) {
    }

    Pyright(Ide ide, Workspace workspace, Path notebook, Consumer<CellDiagnostics> onDiagnostics) {
        this.ide = ide;
        this.workspace = workspace;
        this.notebook = notebook;
        this.onDiagnostics = onDiagnostics;
    }

    /**
     * The Python plugin's pyright launcher, when that plugin is there and pyright is.
     *
     * <p>Empty rather than an error when it is not: a notebook still opens, still runs and
     * still saves without a type checker, and offering to install one is the Python
     * plugin's job and already happens when a {@code .py} is opened.
     */
    static Optional<LanguageServerLauncher> launcher(Ide ide) {
        return ide.languages().byId("python")
                .flatMap(LanguageSupport::languageServer)
                .filter(launcher -> launcher.isInstalled(ide));
    }

    /** Whether there is anything to talk to; false means completion is simply absent. */
    boolean isAvailable() {
        return !broken && launcher(ide).isPresent();
    }

    /** The in-memory Python file pyright is shown, which is never written to disk. */
    private String uri() {
        return notebook.toUri() + ".py";
    }

    private synchronized LanguageServer connect() throws Exception {
        if (server != null) {
            return server;
        }
        LanguageServerLauncher launcher = launcher(ide)
                .orElseThrow(() -> new IllegalStateException("pyright is not installed"));
        List<String> command = launcher.command(ide, workspace);
        ProcessBuilder builder = new ProcessBuilder(command);
        Path cwd = launcher.workingDirectory(ide, workspace);
        if (cwd != null && Files.isDirectory(cwd)) {
            builder.directory(cwd.toFile());
        }
        builder.environment().putAll(launcher.environment(ide, workspace));
        // Its own log, beside the one the core keeps for the pyright it runs for .py files.
        Path logs = ide.homeDir().resolve("logs");
        Files.createDirectories(logs);
        builder.redirectError(logs.resolve("notebook-pyright.log").toFile());
        process = builder.start();

        org.eclipse.lsp4j.jsonrpc.Launcher<LanguageServer> rpc = LSPLauncher.createClientLauncher(
                new Client(), process.getInputStream(), process.getOutputStream());
        rpc.startListening();
        LanguageServer connected = rpc.getRemoteProxy();

        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setRootUri(workspace == null ? null : workspace.root().toUri().toString());
        params.setCapabilities(capabilities());
        connected.initialize(params).get(60, TimeUnit.SECONDS);
        connected.initialized(new InitializedParams());
        server = connected;
        return connected;
    }

    private static ClientCapabilities capabilities() {
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        // Full-text synchronisation: a cell edit changes the joined text in ways that are
        // not a single range - inserting a cell shifts everything below it - and the whole
        // of a notebook's code is small enough that sending all of it is not worth the
        // bookkeeping that incremental updates would need.
        text.setSynchronization(new SynchronizationCapabilities(false, false, true));
        CompletionCapabilities completion = new CompletionCapabilities(
                new CompletionItemCapabilities(false));
        completion.getCompletionItem().setDocumentationFormat(
                List.of(MarkupKind.PLAINTEXT, MarkupKind.MARKDOWN));
        text.setCompletion(completion);
        text.setPublishDiagnostics(new PublishDiagnosticsCapabilities(false));
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        return capabilities;
    }

    /**
     * Tells pyright what the notebook now holds, and asks it to check it.
     *
     * <p>Called after an edit has settled. The context is remembered so that the
     * diagnostics pyright publishes - which arrive later, unprompted, and name lines of
     * the joined file - can be put back on the cells they belong to.
     */
    void update(CellContext context) {
        if (!isAvailable() || context == null) {
            return;
        }
        threads.execute(() -> {
            try {
                LanguageServer connected = connect();
                sent = context;
                if (!opened) {
                    connected.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                            new TextDocumentItem(uri(), "python", version.incrementAndGet(),
                                    context.text())));
                    opened = true;
                } else {
                    connected.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                            new VersionedTextDocumentIdentifier(uri(), version.incrementAndGet()),
                            List.of(new TextDocumentContentChangeEvent(context.text()))));
                }
            } catch (Exception e) {
                giveUp(e);
            }
        });
    }

    /**
     * What to offer at a position in a cell.
     *
     * <p>Synchronous with a short deadline, and called off the UI thread by the caller.
     * pyright is fast on a file this size; if it is not - a first request while it is still
     * indexing the project - nothing is offered, which is better than a popup that arrives
     * after the reader has typed three more characters.
     */
    List<CompletionItem> completionsAt(CellContext context, int cellIndex, int line, int column) {
        if (!isAvailable() || context == null) {
            return List.of();
        }
        int joinedLine = context.lineOf(cellIndex, line);
        if (joinedLine < 0) {
            return List.of();
        }
        try {
            LanguageServer connected = connect();
            sent = context;
            if (!opened) {
                connected.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                        new TextDocumentItem(uri(), "python", version.incrementAndGet(),
                                context.text())));
                opened = true;
            } else {
                connected.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(uri(), version.incrementAndGet()),
                        List.of(new TextDocumentContentChangeEvent(context.text()))));
            }
            CompletionParams params = new CompletionParams(
                    new TextDocumentIdentifier(uri()), new Position(joinedLine, column));
            CompletableFuture<Either<List<CompletionItem>, CompletionList>> answer =
                    connected.getTextDocumentService().completion(params);
            Either<List<CompletionItem>, CompletionList> either =
                    answer.get(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (either == null) {
                return List.of();
            }
            return either.isLeft() ? either.getLeft() : either.getRight().getItems();
        } catch (java.util.concurrent.TimeoutException e) {
            // Still thinking. Nothing offered this time; the next keystroke asks again.
            return List.of();
        } catch (Exception e) {
            giveUp(e);
            return List.of();
        }
    }

    private void giveUp(Exception e) {
        broken = true;
        ide.notifications().warn("Notebook completion is off",
                "pyright could not be started for this notebook: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    /** Stops the process. Called when the editor closes. */
    void dispose() {
        threads.shutdownNow();
        LanguageServer connected = server;
        server = null;
        if (connected != null) {
            try {
                connected.shutdown().get(2, TimeUnit.SECONDS);
                connected.exit();
            } catch (Exception e) {
                // It is going away either way.
            }
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Turns one published diagnostic list into one list per cell.
     *
     * <p>Static and given its context, so the mapping - the part that can be off by one -
     * is tested without a pyright. A diagnostic on a line that belongs to no cell is
     * dropped: it can only be the trailing newline this adds to the last cell.
     */
    static List<CellDiagnostics> spread(CellContext context, List<Diagnostic> diagnostics) {
        if (context == null || diagnostics == null) {
            return List.of();
        }
        java.util.Map<Integer, List<com.smide.api.problems.Diagnostic>> byCell =
                new java.util.LinkedHashMap<>();
        // Every cell in the context gets an entry, so that a cell whose last problem has
        // just been fixed is told to clear its underlines rather than keeping them.
        for (int line = 0; line < context.lineCount(); line++) {
            context.at(line).ifPresent(at ->
                    byCell.computeIfAbsent(at.cellIndex(), key -> new java.util.ArrayList<>()));
        }
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.getRange() == null) {
                continue;
            }
            int from = diagnostic.getRange().getStart().getLine();
            Optional<CellContext.At> at = context.at(from);
            if (at.isEmpty()) {
                continue;
            }
            int cell = at.get().cellIndex();
            int startLine = at.get().line();
            // A problem that spans cells is clipped to the cell it starts in: there is no
            // such thing as a diagnostic over two cells, and the end is only used to
            // underline.
            int endLine = context.at(diagnostic.getRange().getEnd().getLine())
                    .filter(end -> end.cellIndex() == cell)
                    .map(CellContext.At::line)
                    .orElse(startLine);
            byCell.computeIfAbsent(cell, key -> new java.util.ArrayList<>())
                    .add(new com.smide.api.problems.Diagnostic(
                            null,
                            startLine,
                            diagnostic.getRange().getStart().getCharacter(),
                            endLine,
                            diagnostic.getRange().getEnd().getCharacter(),
                            severityOf(diagnostic),
                            diagnostic.getMessage() == null ? "" : diagnostic.getMessage(),
                            "pyright",
                            null));
        }
        return byCell.entrySet().stream()
                .map(entry -> new CellDiagnostics(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private static com.smide.api.problems.Diagnostic.Severity severityOf(Diagnostic diagnostic) {
        if (diagnostic.getSeverity() == null) {
            return com.smide.api.problems.Diagnostic.Severity.WARNING;
        }
        return switch (diagnostic.getSeverity()) {
            case Error -> com.smide.api.problems.Diagnostic.Severity.ERROR;
            case Warning -> com.smide.api.problems.Diagnostic.Severity.WARNING;
            default -> com.smide.api.problems.Diagnostic.Severity.INFO;
        };
    }

    /** The half of the protocol pyright talks to us; all of it but diagnostics is logged. */
    private final class Client implements LanguageClient {

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            // Not string equality: pyright answers with a URI of its own spelling, and
            // {@link Uris} says what that costs when it is compared as text.
            if (params == null || !Uris.same(uri(), params.getUri())) {
                return;
            }
            CellContext context = sent;
            if (context == null) {
                return;
            }
            spread(context, params.getDiagnostics()).forEach(onDiagnostics);
        }

        @Override
        public void showMessage(MessageParams params) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }
    }
}
