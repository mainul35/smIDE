package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.editor.Editor;
import com.smide.core.ExtensionRegistry;
import com.smide.editor.CodeEditor;
import com.smide.search.QuickPopup;
import javafx.application.Platform;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The Code and Navigate actions that go through the language server. */
public final class LspActions {

    private LspActions() {
    }

    public static void register(Ide ide, LspManager manager, ExtensionRegistry registry) {
        registry.addAction(Action.of("code.completion", "Basic Completion").menu("Code").shortcut("shortcut+SPACE")
                .order(100).enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> bound(manager, ctx).ifPresent(b -> b.completion().request(true))));
        registry.addAction(Action.of("code.quickDoc", "Quick Documentation").menu("Code").shortcut("shortcut+Q")
                .order(101).enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> bound(manager, ctx).ifPresent(b -> b.hover().showAtCaret())));
        registry.addAction(Action.of("code.reformat", "Reformat Code").menu("Code").shortcut("shortcut+alt+L")
                .icon("fth-align-left").order(200).enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> reformat(ide, manager, c))));
        registry.addAction(Action.of("code.codeActions", "Show Context Actions").menu("Code").shortcut("alt+ENTER")
                .order(201).enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> codeActions(ide, manager, c))));
        // Enabled without a language server too: a plugin may know where a name leads when no server does.
        registry.addAction(Action.of("navigate.declaration", "Go to Declaration").menu("Navigate").shortcut("shortcut+B")
                .order(40).enabledWhen(ctx -> bound(manager, ctx).isPresent()
                        || code(ctx).isPresent() && !registry.declarationProviders().isEmpty())
                .perform(ctx -> code(ctx).ifPresent(c -> gotoDefinition(ide, manager, c))));
        registry.addAction(Action.of("navigate.usages", "Find Usages").menu("Navigate").shortcut("alt+F7").order(41)
                .enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> findUsages(ide, manager, c))));
        registry.addAction(Action.of("refactor.rename", "Rename...").menu("Refactor").shortcut("shift+F6").order(10)
                .enabledWhen(ctx -> bound(manager, ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> rename(ide, manager, c))));
        registry.addAction(Action.of("code.restartServer", "Restart Language Server").menu("Code").order(900)
                .enabledWhen(ctx -> code(ctx).flatMap(c -> c.language().languageServer()).isPresent())
                .perform(ctx -> ctx.editor().ifPresent(manager::restart)));
    }

    private static Optional<CodeEditor> code(ActionContext ctx) {
        return ctx.editor().filter(e -> e instanceof CodeEditor).map(e -> (CodeEditor) e);
    }

    private static Optional<EditorLspBinding> bound(LspManager manager, ActionContext ctx) {
        return ctx.editor().flatMap(manager::bindingOf).filter(b -> b.session().isReady());
    }

    private static Optional<EditorLspBinding> ready(LspManager manager, Editor editor) {
        return manager.bindingOf(editor).filter(b -> b.session().isReady());
    }

    private static TextDocumentIdentifier id(CodeEditor editor) {
        return new TextDocumentIdentifier(Positions.uri(editor.path()));
    }

    // -------------------------------------------------------------- definition

    /**
     * The characters to draw as a link under Ctrl at this offset, if the language server can
     * go anywhere from here; empty otherwise, or while there is no server ready to ask.
     *
     * <p>The span is the one the server says it resolved from, when it says - a
     * {@code LocationLink} carries it - and otherwise the identifier under the pointer.
     */
    public static java.util.concurrent.CompletableFuture<Optional<int[]>> linkAt(LspManager manager,
                                                                                  CodeEditor editor, int offset) {
        Optional<EditorLspBinding> b = ready(manager, editor);
        int[] word = wordAt(editor.text(), offset);
        if (b.isEmpty() || word == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        b.get().flush();
        Position at = new Position(editor.lineOf(offset), editor.columnOf(offset));
        return b.get().session().server().getTextDocumentService()
                .definition(new DefinitionParams(id(editor), at))
                .orTimeout(5, TimeUnit.SECONDS)
                .handle((result, error) -> {
                    if (error != null || targets(result).isEmpty()) {
                        return Optional.<int[]>empty();
                    }
                    if (result.isRight() && !result.getRight().isEmpty()
                            && result.getRight().get(0).getOriginSelectionRange() != null) {
                        Range origin = result.getRight().get(0).getOriginSelectionRange();
                        return Optional.of(new int[] {
                                editor.offsetOf(origin.getStart().getLine(), origin.getStart().getCharacter()),
                                editor.offsetOf(origin.getEnd().getLine(), origin.getEnd().getCharacter())});
                    }
                    return Optional.of(word);
                });
    }

    /** The identifier the offset is in, or null where there is none. */
    static int[] wordAt(String text, int offset) {
        if (offset < 0 || offset >= text.length() || !isWordChar(text.charAt(offset))) {
            return null;
        }
        int start = offset;
        int end = offset;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return new int[] {start, end};
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    public static void gotoDefinition(Ide ide, LspManager manager, CodeEditor editor) {
        if (declaredByPlugin(ide, editor)) {
            return;
        }
        Optional<EditorLspBinding> b = ready(manager, editor);
        if (b.isEmpty()) {
            ide.statusBar().message(manager.bindingOf(editor).isPresent()
                    ? editor.language().displayName() + " language server is still starting."
                    : "Go to declaration needs a language server for " + editor.language().displayName() + ".");
            return;
        }
        b.get().flush();
        LspSession session = b.get().session();
        session.server().getTextDocumentService().definition(new DefinitionParams(id(editor), Positions.caret(editor)))
                /* Generous, because the first navigation into a dependency can make the
                   server fetch a source jar. A short fuse here reported "failed: null" -
                   our own timeout, wearing the server's clothes. */
                .orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        ide.statusBar().message("Go to declaration failed: " + describe(error));
                        return;
                    }
                    List<Target> targets = targets(result);
                    /* Standing on the declaration already, which is where the answer to
                       "go to the declaration" is the line the caret is on. What somebody
                       wants there is the other direction: who calls this. A server that
                       answers with the declaration itself and one that answers with
                       nothing both mean the same thing, so both go the same way. */
                    if (targets.isEmpty() || atCaret(targets, editor)) {
                        ide.statusBar().message("Already at the declaration - finding usages...");
                        findUsages(ide, manager, editor);
                        return;
                    }
                    if (targets.size() == 1) {
                        open(ide, session, targets.get(0), editor.path());
                    } else {
                        choose(ide, session, "Declarations", targets);
                    }
                }));
    }

    /**
     * Asks the plugins that know a kind of file where the name under the caret leads.
     *
     * <p>First, because a language server that understands the syntax of a file need not
     * understand what its names mean: the XML server has nothing to say about an
     * {@code <artifactId>}, and asked anyway it answers with nothing, which is then read as
     * "already at the declaration". Returns whether a plugin answered - with a place to go,
     * or with why there is none.
     */
    public static boolean declaredByPlugin(Ide ide, CodeEditor editor) {
        if (!(ide instanceof com.smide.core.IdeImpl impl)) {
            return false;
        }
        String text = editor.text();
        int offset = editor.caretOffset();
        for (com.smide.api.editor.DeclarationProvider provider : impl.registry().declarationProviders()) {
            Optional<com.smide.api.editor.DeclarationProvider.Declaration> found;
            try {
                found = provider.declarationAt(editor.path(), text, offset);
            } catch (RuntimeException e) {
                System.err.println("smIDE: a declaration provider failed: " + e);
                continue;
            }
            if (found.isEmpty()) {
                continue;
            }
            com.smide.api.editor.DeclarationProvider.Declaration declaration = found.get();
            if (declaration.file() == null) {
                ide.statusBar().message(declaration.unavailable());
            } else {
                ide.editors().open(declaration.file(), declaration.line(), declaration.column());
            }
            return true;
        }
        return false;
    }

    /**
     * Opens a file pulled out of a sources jar, on the type's own declaration.
     *
     * <p>The line the server reported belongs to a class file it could not read, so it is
     * no guide at all; the declaration is found in the text instead.
     */
    private static void openLibraryFile(Ide ide, Path file, Target t) {
        int line = 0;
        try {
            String name = shortName(t.uri());
            int dot = name.lastIndexOf('.');
            line = LibrarySources.declarationLine(java.nio.file.Files.readString(file),
                    dot < 0 ? name : name.substring(0, dot));
        } catch (java.io.IOException ignored) {
            // Open it at the top.
        }
        com.smide.api.editor.Editor opened = ide.editors().open(file, line, 0);
        /* Again on the next pulse: the tab was created this instant, and a scroll asked
           for before the area has been laid out goes nowhere - which left the reader
           looking at the licence header instead of the class. */
        int declaration = line;
        if (opened != null && declaration > 0) {
            ide.window().runLater(() -> opened.asText().ifPresent(text -> {
                int offset = text.offsetOf(declaration, 0);
                text.select(offset, offset);
            }));
        }
    }

    /**
     * The class a jdt: URI names, for a message that says what could not be opened.
     *
     * <p>The query is cut off first. It carries the classpath entry, encoded, and that
     * contains slashes of its own - so looking for the last slash in the whole URI found
     * one deep inside the query and returned a fragment of a Windows path.
     */
    private static String shortName(String uri) {
        String path = uri;
        int question = path.indexOf('?');
        if (question >= 0) {
            path = path.substring(0, question);
        }
        int slash = path.lastIndexOf('/');
        String tail = slash < 0 ? path : path.substring(slash + 1);
        return tail.isBlank() ? uri : tail;
    }

    /** A failure the user can act on: some of these carry no message at all. */
    private static String describe(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "the language server did not answer in time. It may be fetching sources for a dependency.";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * Somewhere to jump to. {@code uri} is kept because a declaration inside a library is
     * not a file at all - JDT answers with {@code jdt://...} and hands over the source
     * only when asked.
     */
    private record Target(Path file, int line, int column, String preview, String uri) {
    }

    private static List<Target> targets(Either<List<? extends Location>, List<? extends LocationLink>> result) {
        List<Target> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        if (result.isLeft()) {
            for (Location l : result.getLeft()) {
                out.add(target(l));
            }
        } else {
            for (LocationLink l : result.getRight()) {
                Range r = l.getTargetSelectionRange() != null ? l.getTargetSelectionRange() : l.getTargetRange();
                out.add(new Target(pathOrNull(l.getTargetUri()), r.getStart().getLine(),
                        r.getStart().getCharacter(), "", l.getTargetUri()));
            }
        }
        return out;
    }

    private static Target target(Location l) {
        return new Target(pathOrNull(l.getUri()), l.getRange().getStart().getLine(),
                l.getRange().getStart().getCharacter(), "", l.getUri());
    }

    /** The local file a URI names, or null when it names something inside a library. */
    private static Path pathOrNull(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return null;
        }
        try {
            return Positions.path(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void open(Ide ide, LspSession session, Target t) {
        open(ide, session, t, null);
    }

    /**
     * @param fileInProject the file the navigation started from, which says which project
     *                      to reconfigure if a source jar has to be fetched
     */
    private static void open(Ide ide, LspSession session, Target t, Path fileInProject) {
        if (t.file() != null && java.nio.file.Files.isRegularFile(t.file())) {
            ide.editors().open(t.file(), t.line(), t.column());
            return;
        }
        openFromServer(ide, session, t, fileInProject, true);
    }

    /**
     * Opens a declaration that lives inside a dependency.
     *
     * <p>The server holds the source (attached or decompiled) behind its own URI scheme,
     * so it is fetched, written under {@code ~/.smide/libraries} and opened from there.
     * A real file means every editor feature keeps working; the copy is disposable and is
     * simply overwritten next time.
     */
    private static void openFromServer(Ide ide, LspSession session, Target t) {
        openFromServer(ide, session, t, null, true);
    }

    private static void openFromServer(Ide ide, LspSession session, Target t, Path fileInProject,
                                       boolean mayFetchSources) {
        if (t.uri() == null) {
            ide.statusBar().message("No declaration found");
            return;
        }
        session.sendRequest("java/classFileContents", new TextDocumentIdentifier(t.uri()))
                .orTimeout(45, TimeUnit.SECONDS)
                .whenComplete((contents, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        System.err.println("smIDE: classFileContents failed for " + t.uri() + ": " + error);
                        ide.statusBar().message("Cannot open the declaration: " + describe(error));
                        return;
                    }
                    if (!(contents instanceof String source) || source.isBlank()) {
                        /* No source in the jar. The URI names the artifact, so rather than
                           stopping at "not attached", offer to fetch it and come back. */
                        if (mayFetchSources && LibrarySources.show(ide, session, t.uri(), fileInProject,
                                file -> openLibraryFile(ide, file, t))) {
                            return;
                        }
                        ide.statusBar().message("The declaration is in a library with no source attached: "
                                + shortName(t.uri()));
                        return;
                    }
                    try {
                        Path dir = ide.homeDir().resolve("libraries");
                        java.nio.file.Files.createDirectories(dir);
                        Path file = dir.resolve(nameFor(t.uri()));
                        java.nio.file.Files.writeString(file, source);
                        ide.editors().open(file, t.line(), t.column());
                        ide.statusBar().message("Read-only copy from a library: " + file.getFileName());
                    } catch (java.io.IOException e) {
                        ide.notifications().error("Cannot open library source", e.getMessage());
                    }
                }));
    }

    /** {@code jdt://contents/spring-core.jar/org.springframework/Foo.class?=...} to {@code Foo.java}. */
    private static String nameFor(String uri) {
        String cleaned = uri;
        int query = cleaned.indexOf('?');
        if (query > 0) {
            cleaned = cleaned.substring(0, query);
        }
        int slash = cleaned.lastIndexOf('/');
        String name = slash < 0 ? cleaned : cleaned.substring(slash + 1);
        name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        if (name.endsWith(".class")) {
            name = name.substring(0, name.length() - ".class".length());
        }
        name = name.replaceAll("[^A-Za-z0-9_.$-]", "_");
        return name.endsWith(".java") ? name : name + ".java";
    }

    private static void choose(Ide ide, LspSession session, String title, List<Target> targets) {
        QuickPopup popup = new QuickPopup(title, List.of(), "Enter opens   Esc closes", (tab, text, gen, publish) -> {
            List<QuickPopup.Item> items = new ArrayList<>();
            for (Target t : targets) {
                String name = t.file() != null ? t.file().getFileName().toString() : "library";
                String where = name + ":" + (t.line() + 1);
                String detail = !t.preview().isBlank() ? t.preview()
                        : t.file() != null ? t.file().toString() : t.uri();
                if (text.isBlank() || where.toLowerCase().contains(text.toLowerCase())
                        || detail.toLowerCase().contains(text.toLowerCase())) {
                    items.add(new QuickPopup.Item(where, detail, "fth-file-text", null, () -> open(ide, session, t)));
                }
            }
            publish.apply(gen, items);
        });
        popup.applyTheme(ide.theme());
        popup.show(ide.window().stage(), "");
    }

    /**
     * Whether the one place the server points at is the line the caret is on.
     *
     * <p>By line rather than by column: the caret is somewhere inside the name, and the
     * declaration's range starts at the first character of it. Two declarations of the
     * same name on one line is not a case worth carrying weight for.
     */
    private static boolean atCaret(List<Target> targets, CodeEditor editor) {
        if (targets.size() != 1) {
            return false;
        }
        Target only = targets.get(0);
        return only.file() != null && only.file().equals(editor.path())
                && only.line() == editor.caretLine();
    }

    // ---------------------------------------------------------------- usages

    public static void findUsages(Ide ide, LspManager manager, CodeEditor editor) {
        Optional<EditorLspBinding> b = ready(manager, editor);
        if (b.isEmpty()) {
            return;
        }
        b.get().flush();
        String word = editor.wordAtCaret();
        ReferenceParams params = new ReferenceParams(id(editor), Positions.caret(editor), new ReferenceContext(true));
        b.get().session().server().getTextDocumentService().references(params)
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null || result == null) {
                        ide.statusBar().message("Find usages failed");
                        return;
                    }
                    List<Target> targets = new ArrayList<>();
                    for (Location l : result) {
                        Target t = target(l);
                        targets.add(new Target(t.file(), t.line(), t.column(), previewLine(t), t.uri()));
                    }
                    if (targets.isEmpty()) {
                        ide.statusBar().message("No usages of " + word);
                    } else {
                        choose(ide, b.get().session(), "Usages of " + word + " (" + targets.size() + ")", targets);
                    }
                }));
    }

    private static String previewLine(Target t) {
        if (t.file() == null) {
            return "";
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(t.file());
            return t.line() < lines.size() ? lines.get(t.line()).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- rename

    public static void rename(Ide ide, LspManager manager, CodeEditor editor) {
        Optional<EditorLspBinding> b = ready(manager, editor);
        if (b.isEmpty()) {
            return;
        }
        b.get().flush();
        String current = editor.wordAtCaret();
        Optional<String> name = ide.window().prompt("Rename", "New name for " + current, current);
        if (name.isEmpty() || name.get().equals(current)) {
            return;
        }
        RenameParams params = new RenameParams(id(editor), Positions.caret(editor), name.get());
        b.get().session().server().getTextDocumentService().rename(params)
                .orTimeout(20, TimeUnit.SECONDS)
                .whenComplete((edit, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        ide.notifications().error("Rename failed", error.getMessage());
                        return;
                    }
                    if (WorkspaceEdits.apply(ide, edit)) {
                        ide.statusBar().message("Renamed " + current + " to " + name.get());
                    }
                }));
    }

    // ------------------------------------------------------------ formatting

    public static void reformat(Ide ide, LspManager manager, CodeEditor editor) {
        Optional<EditorLspBinding> b = ready(manager, editor);
        if (b.isEmpty()) {
            return;
        }
        b.get().flush();
        FormattingOptions options = new FormattingOptions(editor.language().indentSize(), !editor.language().useTabs());
        b.get().session().server().getTextDocumentService()
                .formatting(new DocumentFormattingParams(id(editor), options))
                .orTimeout(20, TimeUnit.SECONDS)
                .whenComplete((edits, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        ide.statusBar().message("Reformat failed: " + error.getMessage());
                        return;
                    }
                    if (edits == null || edits.isEmpty()) {
                        ide.statusBar().message("Already formatted");
                        return;
                    }
                    List<TextEdit> sorted = new ArrayList<>(edits);
                    sorted.sort(java.util.Comparator.comparing((TextEdit t) -> t.getRange().getStart().getLine())
                            .thenComparing(t -> t.getRange().getStart().getCharacter()).reversed());
                    WorkspaceEdits.apply(editor, sorted);
                    ide.statusBar().message("Reformatted");
                }));
    }

    // ---------------------------------------------------------- code actions

    public static void codeActions(Ide ide, LspManager manager, CodeEditor editor) {
        Optional<EditorLspBinding> b = ready(manager, editor);
        if (b.isEmpty()) {
            return;
        }
        b.get().flush();
        LspSession session = b.get().session();
        Range range;
        if (editor.selectionEnd() > editor.selectionStart()) {
            range = new Range(Positions.of(editor, editor.selectionStart()), Positions.of(editor, editor.selectionEnd()));
        } else {
            Position p = Positions.caret(editor);
            range = new Range(p, p);
        }
        List<org.eclipse.lsp4j.Diagnostic> diagnostics = new ArrayList<>();
        for (com.smide.api.problems.Diagnostic d : editor.diagnostics()) {
            if (d.startLine() <= range.getEnd().getLine() && d.endLine() >= range.getStart().getLine()) {
                org.eclipse.lsp4j.Diagnostic ld = new org.eclipse.lsp4j.Diagnostic(
                        new Range(new Position(d.startLine(), d.startColumn()), new Position(d.endLine(), d.endColumn())),
                        d.message());
                ld.setSource(d.source());
                if (d.code() != null) {
                    ld.setCode(d.code());
                }
                diagnostics.add(ld);
            }
        }
        CodeActionParams params = new CodeActionParams(id(editor), range, new CodeActionContext(diagnostics));
        session.server().getTextDocumentService().codeAction(params)
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null || result == null || result.isEmpty()) {
                        ide.statusBar().message("No context actions here");
                        return;
                    }
                    QuickPopup popup = new QuickPopup("Context actions", List.of(), "Enter applies   Esc closes",
                            (tab, text, gen, publish) -> {
                                List<QuickPopup.Item> items = new ArrayList<>();
                                for (Either<Command, CodeAction> e : result) {
                                    String title = e.isLeft() ? e.getLeft().getTitle() : e.getRight().getTitle();
                                    String kind = e.isRight() && e.getRight().getKind() != null ? e.getRight().getKind() : "";
                                    if (text.isBlank() || title.toLowerCase().contains(text.toLowerCase())) {
                                        items.add(new QuickPopup.Item(title, kind, "fth-zap", null,
                                                () -> applyCodeAction(ide, session, e)));
                                    }
                                }
                                publish.apply(gen, items);
                            });
                    popup.applyTheme(ide.theme());
        popup.show(ide.window().stage(), "");
                }));
    }

    private static void applyCodeAction(Ide ide, LspSession session, Either<Command, CodeAction> e) {
        if (e.isRight()) {
            CodeAction action = e.getRight();
            if (action.getEdit() != null) {
                WorkspaceEdits.apply(ide, action.getEdit());
            } else if (action.getCommand() == null && session.isReady()) {
                // A lazy action: ask the server to fill in the edit.
                session.server().getTextDocumentService().resolveCodeAction(action)
                        .orTimeout(10, TimeUnit.SECONDS)
                        .whenComplete((resolved, err) -> Platform.runLater(() -> {
                            if (resolved != null && resolved.getEdit() != null) {
                                WorkspaceEdits.apply(ide, resolved.getEdit());
                            }
                            if (resolved != null && resolved.getCommand() != null) {
                                execute(session, resolved.getCommand());
                            }
                        }));
                return;
            }
            if (action.getCommand() != null) {
                execute(session, action.getCommand());
            }
        } else {
            execute(session, e.getLeft());
        }
    }

    private static void execute(LspSession session, Command command) {
        if (session.isReady()) {
            session.server().getWorkspaceService().executeCommand(
                    new ExecuteCommandParams(command.getCommand(), command.getArguments()));
        }
    }

    /** Applies edits from a WorkspaceEdit or reports why not; used by plugins. */
    public static boolean applyEdit(Ide ide, WorkspaceEdit edit) {
        return WorkspaceEdits.apply(ide, edit);
    }
}
