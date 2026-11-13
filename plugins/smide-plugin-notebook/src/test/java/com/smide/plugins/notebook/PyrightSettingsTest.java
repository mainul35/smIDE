package com.smide.plugins.notebook;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What pyright is told about the project, and what it is worth.
 *
 * <p>The mapping half of this is arithmetic and is tested on its own. What is tested here
 * is the one value that decided whether completion worked at all: the interpreter. pyright
 * asks the client for it once, during startup, and a client that does not answer is never
 * asked again - it simply gets a checker that cannot see anything the project installed.
 *
 * <p>The last test is the one that matters and is the only one that starts a process. It
 * is skipped where there is no pyright or no environment to point at, because a machine
 * without them is not a machine where this can be wrong.
 */
class PyrightSettingsTest {

    @Test
    void theInterpreterIsWhatPyrightResolvesImportsAgainst() {
        Map<String, Object> python = Pyright.settings("python", Path.of("C:\\p\\.venv\\Scripts\\python.exe"));
        assertEquals("C:\\p\\.venv\\Scripts\\python.exe", python.get("pythonPath"));
    }

    @Test
    void noInterpreterMeansNoClaimAboutOne() {
        Map<String, Object> python = Pyright.settings("python", null);
        // Absent, not empty or null: a blank pythonPath is a path pyright would try to use.
        assertFalse(python.containsKey("pythonPath"));
        assertTrue(python.containsKey("analysis"));
    }

    @Test
    void askingForEverythingGetsTheSettingsUnderTheirSection() {
        Map<String, Object> all = Pyright.settings(null, Path.of("python.exe"));
        assertTrue(all.containsKey("python"));
        assertEquals(Pyright.settings("python", Path.of("python.exe")), all.get("python"));
        assertEquals(all, Pyright.settings("  ", Path.of("python.exe")));
    }

    @Test
    void askingForOneSectionGetsThatSection() {
        Map<String, Object> analysis = Pyright.settings("python.analysis", null);
        assertEquals(true, analysis.get("autoSearchPaths"));
        assertEquals(true, analysis.get("useLibraryCodeForTypes"));
        // A notebook is a few cells; checking the whole project would make typing wait.
        assertEquals("openFilesOnly", analysis.get("diagnosticMode"));
    }

    @Test
    void aSectionNobodyKnowsIsAnsweredWithNothingRatherThanGuessedAt() {
        assertEquals(Map.of(), Pyright.settings("editor", Path.of("python.exe")));
        assertEquals(Map.of(), Pyright.settings("pyright", null));
    }

    // ------------------------------------------------------------ with a real pyright

    private static final Path PROJECT =
            Path.of("C:\\Users\\mainu\\Documents\\codes\\langgraph_bootcamp");
    private static final Path SERVER = Path.of(System.getProperty("user.home"),
            ".smide", "tools", "node", "node_modules", "pyright", "langserver.index.js");

    /**
     * The whole point, end to end: a package that lives in the project's environment is
     * completable only because pyright was told where that environment is.
     *
     * <p>This is written as one test with both halves in it rather than two, because what
     * is being asserted is the difference between them. Either half alone would pass for
     * the wrong reason - an empty list is also what a broken connection returns.
     */
    @Test
    void tellingPyrightTheEnvironmentIsWhatMakesItsContentsCompletable() throws Exception {
        Path python = PROJECT.resolve(".venv").resolve("Scripts").resolve("python.exe");
        Assumptions.assumeTrue(Files.isRegularFile(SERVER), "pyright is not installed here");
        Assumptions.assumeTrue(Files.isRegularFile(python), "no environment to point at");
        Assumptions.assumeTrue(Files.isDirectory(PROJECT.resolve(".venv")
                .resolve("Lib").resolve("site-packages").resolve("langgraph")),
                "langgraph is not in that environment");

        // Caret immediately after "StateGra", which is where this was first noticed.
        String source = "from langgraph.graph import StateGra\n";
        Position caret = new Position(0, 36);

        List<CompletionItem> toldNothing = completionsFrom(null, source, caret);
        List<CompletionItem> toldTheVenv = completionsFrom(python, source, caret);

        assertTrue(toldNothing.isEmpty(),
                "with no interpreter pyright cannot resolve langgraph, so it has nothing to"
                        + " offer - if this ever stops being true the test below proves less");
        assertTrue(toldTheVenv.stream().anyMatch(item -> "StateGraph".equals(item.getLabel())),
                "expected StateGraph, got " + toldTheVenv.stream()
                        .map(CompletionItem::getLabel).toList());
    }

    /** Starts a pyright, answers its settings request with {@code interpreter}, asks once. */
    private static List<CompletionItem> completionsFrom(Path interpreter, String source,
                                                        Position caret) throws Exception {
        Process process = new ProcessBuilder("node", SERVER.toString(), "--stdio")
                .directory(PROJECT.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            var rpc = LSPLauncher.createClientLauncher(new Answers(interpreter),
                    process.getInputStream(), process.getOutputStream());
            rpc.startListening();
            LanguageServer server = rpc.getRemoteProxy();

            InitializeParams params = new InitializeParams();
            params.setProcessId((int) ProcessHandle.current().pid());
            params.setRootUri(PROJECT.toUri().toString());
            ClientCapabilities capabilities = new ClientCapabilities();
            WorkspaceClientCapabilities pull = new WorkspaceClientCapabilities();
            pull.setConfiguration(true);
            capabilities.setWorkspace(pull);
            params.setCapabilities(capabilities);
            server.initialize(params).get(90, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());

            String uri = PROJECT.resolve("my_agent.ipynb").toUri() + ".py";
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "python", 1, source)));
            Either<List<CompletionItem>, CompletionList> answer = server.getTextDocumentService()
                    .completion(new CompletionParams(new TextDocumentIdentifier(uri), caret))
                    .get(60, TimeUnit.SECONDS);
            if (answer == null) {
                return List.of();
            }
            return answer.isLeft() ? answer.getLeft() : answer.getRight().getItems();
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** A client that does nothing but answer the settings question, the way Pyright does. */
    private record Answers(Path interpreter) implements LanguageClient {

        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            List<Object> answers = new java.util.ArrayList<>();
            if (params != null && params.getItems() != null) {
                params.getItems().forEach(item ->
                        answers.add(Pyright.settings(item.getSection(), interpreter)));
            }
            return CompletableFuture.completedFuture(answers);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        }

        @Override
        public void showMessage(MessageParams message) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }
    }
}
