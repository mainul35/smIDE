package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.problems.Diagnostic;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** What the server calls back into: diagnostics, messages, edits, progress. */
final class LspClientImpl implements LanguageClient {

    private final Ide ide;
    private final LspSession session;
    private final Map<Object, com.smide.api.ui.StatusBar.Progress> progress = new ConcurrentHashMap<>();

    LspClientImpl(Ide ide, LspSession session) {
        this.ide = ide;
        this.session = session;
    }

    @Override
    public void telemetryEvent(Object object) {
        // Not interesting.
    }

    /**
     * JDT's own progress notification.
     *
     * <p>Not part of the protocol, so LSP4J logs a warning for every one it cannot
     * dispatch - several a second while a project imports. Accepting it here keeps the
     * log readable and gives the status bar something useful to say.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/progressReport")
    public void languageProgressReport(Object report) {
        if (report instanceof com.google.gson.JsonObject json) {
            com.google.gson.JsonElement task = json.get("task");
            com.google.gson.JsonElement status = json.get("status");
            String text = status != null && !status.isJsonNull() ? status.getAsString()
                    : task != null && !task.isJsonNull() ? task.getAsString() : null;
            if (text != null && !text.isBlank()) {
                ide.statusBar().message(session.displayName() + ": " + text);
            }
        }
    }

    /** Also JDT's: the status of the server as a whole. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/status")
    public void languageStatus(Object status) {
        // The status widget already reports readiness; this only keeps LSP4J quiet.
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        Path file = Positions.path(params.getUri());
        List<Diagnostic> out = new ArrayList<>();
        for (org.eclipse.lsp4j.Diagnostic d : params.getDiagnostics()) {
            out.add(new Diagnostic(file,
                    d.getRange().getStart().getLine(), d.getRange().getStart().getCharacter(),
                    d.getRange().getEnd().getLine(), d.getRange().getEnd().getCharacter(),
                    severity(d.getSeverity()), d.getMessage(),
                    d.getSource() == null ? session.serverId() : d.getSource(),
                    d.getCode() == null ? null : d.getCode().isLeft() ? d.getCode().getLeft()
                            : String.valueOf(d.getCode().getRight())));
        }
        ide.problems().set("lsp:" + session.serverId(), file, out);
        ProjectReload.noticeStale(ide, session, file, out);
    }

    private static Diagnostic.Severity severity(DiagnosticSeverity s) {
        if (s == null) {
            return Diagnostic.Severity.ERROR;
        }
        return switch (s) {
            case Error -> Diagnostic.Severity.ERROR;
            case Warning -> Diagnostic.Severity.WARNING;
            case Information -> Diagnostic.Severity.INFO;
            case Hint -> Diagnostic.Severity.HINT;
        };
    }

    @Override
    public void showMessage(MessageParams params) {
        String title = session.displayName();
        switch (params.getType()) {
            case Error -> ide.notifications().error(title, params.getMessage());
            case Warning -> ide.notifications().warn(title, params.getMessage());
            default -> ide.statusBar().message(title + ": " + params.getMessage());
        }
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        CompletableFuture<MessageActionItem> future = new CompletableFuture<>();
        ide.window().runLater(() -> {
            List<MessageActionItem> actions = params.getActions() == null ? List.of() : params.getActions();
            if (actions.isEmpty()) {
                showMessage(params);
                future.complete(null);
                return;
            }
            com.smide.api.ui.Notifications.NotificationAction[] buttons =
                    new com.smide.api.ui.Notifications.NotificationAction[actions.size()];
            for (int i = 0; i < actions.size(); i++) {
                MessageActionItem item = actions.get(i);
                buttons[i] = new com.smide.api.ui.Notifications.NotificationAction(item.getTitle(),
                        () -> future.complete(item));
            }
            if (params.getType() == MessageType.Error) {
                ide.notifications().error(session.displayName(), params.getMessage(), buttons);
            } else {
                ide.notifications().warn(session.displayName(), params.getMessage(), buttons);
            }
        });
        return future;
    }

    @Override
    public void logMessage(MessageParams params) {
        session.log(params.getType() + ": " + params.getMessage());
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        CompletableFuture<ApplyWorkspaceEditResponse> future = new CompletableFuture<>();
        ide.window().runLater(() -> {
            boolean ok = WorkspaceEdits.apply(ide, params.getEdit());
            future.complete(new ApplyWorkspaceEditResponse(ok));
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < params.getItems().size(); i++) {
            out.add(session.configurationFor(params.getItems().get(i).getSection()));
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return CompletableFuture.completedFuture(session.workspaceFolders());
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        Object token = params.getToken().isLeft() ? params.getToken().getLeft() : params.getToken().getRight();
        Either<WorkDoneProgressNotification, Object> value = params.getValue();
        if (value == null || !value.isLeft()) {
            return;
        }
        WorkDoneProgressNotification n = value.getLeft();
        if (n instanceof WorkDoneProgressBegin begin) {
            com.smide.api.ui.StatusBar.Progress p = ide.statusBar().progress(
                    session.displayName() + ": " + begin.getTitle(), Boolean.TRUE.equals(begin.getCancellable()));
            progress.put(token, p);
            // What the protocol has for this: the server is told, and ends its own progress.
            p.onCancel(cancelled -> {
                progress.remove(token, cancelled);
                org.eclipse.lsp4j.services.LanguageServer server = session.server();
                if (server != null && Boolean.TRUE.equals(begin.getCancellable())) {
                    server.cancelProgress(new org.eclipse.lsp4j.WorkDoneProgressCancelParams(params.getToken()));
                }
            });
            p.update(begin.getMessage(), begin.getPercentage() == null ? -1 : begin.getPercentage() / 100.0);
        } else if (n instanceof WorkDoneProgressReport report) {
            com.smide.api.ui.StatusBar.Progress p = progress.get(token);
            if (p != null) {
                p.update(report.getMessage(), report.getPercentage() == null ? -1 : report.getPercentage() / 100.0);
            }
        } else if (n instanceof WorkDoneProgressEnd) {
            com.smide.api.ui.StatusBar.Progress p = progress.remove(token);
            if (p != null) {
                p.done();
            }
        }
    }

    /**
     * Ends every progress this server had begun.
     *
     * <p>For when the server stops: what it was doing has stopped with it, and its tasks
     * would otherwise keep counting in the status bar for as long as the window is open.
     */
    void endProgress() {
        for (com.smide.api.ui.StatusBar.Progress p : progress.values()) {
            p.done();
        }
        progress.clear();
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlineValues() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        return CompletableFuture.completedFuture(null);
    }
}
