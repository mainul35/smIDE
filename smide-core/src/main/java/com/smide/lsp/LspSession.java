package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.workspace.Workspace;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One language server process serving one workspace (or every workspace, when the
 * launcher says so): started, initialised, fed documents, and shut down.
 */
public final class LspSession {

    public enum State {
        STARTING, READY, FAILED, STOPPED
    }

    private final Ide ide;
    private final LanguageServerLauncher launcher;
    private final Workspace workspace;
    private final List<Workspace> workspaces = new ArrayList<>();
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private final List<Consumer<State>> stateListeners = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private final ExecutorService executor;
    private Process process;
    private LanguageServer server;
    private org.eclipse.lsp4j.jsonrpc.Endpoint endpoint;
    private ServerCapabilities capabilities;
    private volatile State state = State.STARTING;
    private String failure;
    private Path logFile;

    LspSession(Ide ide, LanguageServerLauncher launcher, Workspace workspace) {
        this.ide = ide;
        this.launcher = launcher;
        this.workspace = workspace;
        if (workspace != null) {
            workspaces.add(workspace);
        }
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smide-lsp-" + launcher.serverId());
            t.setDaemon(true);
            return t;
        });
    }

    public String serverId() {
        return launcher.serverId();
    }

    public String displayName() {
        return launcher.displayName();
    }

    public State state() {
        return state;
    }

    public String failure() {
        return failure;
    }

    public LanguageServer server() {
        return server;
    }

    public ServerCapabilities capabilities() {
        return capabilities;
    }

    public Workspace workspace() {
        return workspace;
    }

    public LanguageServerLauncher launcher() {
        return launcher;
    }

    /**
     * Sends a request the protocol does not define.
     *
     * <p>Servers add their own methods - JDT's {@code java/classFileContents} is the one
     * that makes navigating into a library possible - and the typed proxy only knows the
     * standard ones. The raw endpoint takes any method name, so the core stays free of
     * per-language interfaces.
     */
    public java.util.concurrent.CompletableFuture<Object> sendRequest(String method, Object params) {
        if (endpoint == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException(displayName() + " is not connected"));
        }
        // Endpoint.request is declared with a wildcard result; the caller checks the type.
        return endpoint.request(method, params).thenApply(value -> (Object) value);
    }

    public void addStateListener(Consumer<State> listener) {
        stateListeners.add(listener);
    }

    private void setState(State s, String why) {
        state = s;
        failure = why;
        ide.window().runLater(() -> stateListeners.forEach(l -> l.accept(s)));
    }

    List<WorkspaceFolder> workspaceFolders() {
        List<WorkspaceFolder> out = new ArrayList<>();
        for (Workspace w : workspaces) {
            out.add(new WorkspaceFolder(Positions.uri(w.root()), w.name()));
        }
        return out;
    }

    Object configurationFor(String section) {
        return null;
    }

    void log(String line) {
        synchronized (logBuffer) {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > 500_000) {
                logBuffer.delete(0, 250_000);
            }
        }
        if (logFile != null) {
            try {
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Logging is best effort.
            }
        }
    }

    public String logText() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }

    // ------------------------------------------------------------------ start

    /** Starts the process and initialises the protocol. Background thread. */
    CompletableFuture<Void> start() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                List<String> command = launcher.command(ide, workspace);
                Path cwd = launcher.workingDirectory(ide, workspace);
                ProcessBuilder pb = new ProcessBuilder(command);
                if (cwd != null && Files.isDirectory(cwd)) {
                    pb.directory(cwd.toFile());
                }
                pb.environment().putAll(launcher.environment(ide, workspace));
                Path logs = ide.homeDir().resolve("logs");
                Files.createDirectories(logs);
                logFile = logs.resolve(launcher.serverId() + ".log");
                pb.redirectError(logFile.toFile());
                log("Starting: " + String.join(" ", command));
                process = pb.start();

                InputStream in = process.getInputStream();
                OutputStream out = process.getOutputStream();
                LspClientImpl client = new LspClientImpl(ide, this);
                Launcher<LanguageServer> jsonRpc = LSPLauncher.createClientLauncher(client, in, out);
                jsonRpc.startListening();
                server = jsonRpc.getRemoteProxy();
                endpoint = jsonRpc.getRemoteEndpoint();

                InitializeParams params = new InitializeParams();
                params.setProcessId((int) ProcessHandle.current().pid());
                params.setClientInfo(new ClientInfo("smIDE", ide.version()));
                if (workspace != null) {
                    params.setRootUri(Positions.uri(workspace.root()));
                }
                params.setWorkspaceFolders(workspaceFolders());
                params.setCapabilities(clientCapabilities());
                Object options = launcher.initializationOptions(ide, workspace);
                if (options != null) {
                    params.setInitializationOptions(options);
                }
                InitializeResult result = server.initialize(params).get(120, TimeUnit.SECONDS);
                capabilities = result.getCapabilities();
                server.initialized(new InitializedParams());
                /* An empty object rather than nothing when a launcher has no options.
                   Several servers - the YAML one among them - do no work until a
                   configuration has been pushed, and the class that carries it refuses a
                   null settings value outright: "Property must not be null: settings"
                   was thrown here, took the session down with it, and the language came
                   up as failed for no reason the user could see. */
                server.getWorkspaceService().didChangeConfiguration(
                        new DidChangeConfigurationParams(options == null ? Map.of() : options));
                setState(State.READY, null);
                done.complete(null);
                process.onExit().thenAccept(p -> {
                    if (state != State.STOPPED) {
                        setState(State.FAILED, "exited with code " + p.exitValue());
                    }
                });
            } catch (Exception e) {
                log("Start failed: " + e);
                setState(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
                done.completeExceptionally(e);
                if (process != null) {
                    process.destroy();
                }
            }
        });
        return done;
    }

    private static ClientCapabilities clientCapabilities() {
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        SynchronizationCapabilities sync = new SynchronizationCapabilities(false, false, true);
        text.setSynchronization(sync);
        CompletionCapabilities completion = new CompletionCapabilities(new CompletionItemCapabilities(false));
        completion.getCompletionItem().setDocumentationFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        completion.setContextSupport(true);
        text.setCompletion(completion);
        HoverCapabilities hover = new HoverCapabilities(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT), false);
        text.setHover(hover);
        text.setDefinition(new DefinitionCapabilities(false));
        text.setReferences(new ReferencesCapabilities(false));
        text.setRename(new RenameCapabilities(false, false));
        text.setFormatting(new FormattingCapabilities(false));
        DocumentSymbolCapabilities symbols = new DocumentSymbolCapabilities(false);
        symbols.setHierarchicalDocumentSymbolSupport(true);
        text.setDocumentSymbol(symbols);
        CodeActionCapabilities codeAction = new CodeActionCapabilities(new CodeActionLiteralSupportCapabilities(
                new CodeActionKindCapabilities(List.of(CodeActionKind.QuickFix, CodeActionKind.Refactor,
                        CodeActionKind.RefactorExtract, CodeActionKind.RefactorInline, CodeActionKind.RefactorRewrite,
                        CodeActionKind.Source, CodeActionKind.SourceOrganizeImports))), false);
        text.setCodeAction(codeAction);
        text.setPublishDiagnostics(new PublishDiagnosticsCapabilities(false));

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        WorkspaceEditCapabilities edit = new WorkspaceEditCapabilities();
        edit.setDocumentChanges(true);
        edit.setResourceOperations(List.of("create", "rename", "delete"));
        workspace.setWorkspaceEdit(edit);
        workspace.setConfiguration(true);
        workspace.setWorkspaceFolders(true);
        workspace.setExecuteCommand(new org.eclipse.lsp4j.ExecuteCommandCapabilities(false));

        WindowClientCapabilities window = new WindowClientCapabilities();
        window.setWorkDoneProgress(true);

        ClientCapabilities caps = new ClientCapabilities(workspace, text, null);
        caps.setWindow(window);
        return caps;
    }

    // ------------------------------------------------------------- documents

    public boolean isReady() {
        return state == State.READY && server != null;
    }

    public void didOpen(Path file, LanguageSupport language, String text) {
        if (!isReady()) {
            return;
        }
        String uri = Positions.uri(file);
        versions.put(uri, 1);
        run(() -> server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, language.lspLanguageId(), 1, text))));
    }

    public void didChange(Path file, String text) {
        if (!isReady()) {
            return;
        }
        String uri = Positions.uri(file);
        int version = versions.merge(uri, 1, Integer::sum);
        run(() -> server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(text)))));
    }

    public void didSave(Path file, String text) {
        if (!isReady()) {
            return;
        }
        String uri = Positions.uri(file);
        boolean includeText = capabilities != null && capabilities.getTextDocumentSync() != null
                && capabilities.getTextDocumentSync().isRight()
                && capabilities.getTextDocumentSync().getRight().getSave() != null
                && capabilities.getTextDocumentSync().getRight().getSave().isRight()
                && Boolean.TRUE.equals(capabilities.getTextDocumentSync().getRight().getSave().getRight().getIncludeText());
        DidSaveTextDocumentParams params = new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri));
        if (includeText) {
            params.setText(text);
        }
        run(() -> server.getTextDocumentService().didSave(params));
    }

    public void didClose(Path file) {
        if (!isReady()) {
            return;
        }
        String uri = Positions.uri(file);
        versions.remove(uri);
        run(() -> server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri))));
        ide.problems().set("lsp:" + serverId(), file, List.of());
    }

    /** Whether the server wants full documents on change (the only mode we send). */
    public boolean supportsIncrementalSync() {
        return capabilities != null && capabilities.getTextDocumentSync() != null
                && ((capabilities.getTextDocumentSync().isLeft()
                && capabilities.getTextDocumentSync().getLeft() == TextDocumentSyncKind.Incremental)
                || (capabilities.getTextDocumentSync().isRight()
                && capabilities.getTextDocumentSync().getRight().getChange() == TextDocumentSyncKind.Incremental));
    }

    private void run(Runnable r) {
        executor.execute(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log("Request failed: " + e);
            }
        });
    }

    // -------------------------------------------------------------------- stop

    public void stop() {
        if (state == State.STOPPED) {
            return;
        }
        setState(State.STOPPED, null);
        executor.execute(() -> {
            try {
                if (server != null) {
                    try {
                        server.shutdown().get(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // Server may already be gone.
                    }
                    server.exit();
                }
            } finally {
                if (process != null) {
                    if (!waitQuietly(process, 2)) {
                        process.destroyForcibly();
                    }
                }
                executor.shutdown();
            }
        });
        ide.problems().clear("lsp:" + serverId());
    }

    private static boolean waitQuietly(Process p, int seconds) {
        try {
            return p.waitFor(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
