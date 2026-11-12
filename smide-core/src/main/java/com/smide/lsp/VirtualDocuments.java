package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.lang.EmbeddedRegion;
import com.smide.api.lang.LanguageSupport;
import com.smide.editor.CodeEditor;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The other languages in a file, handed to the servers that know them.
 *
 * <p>Asked directly, the HTML language server reports nothing at all about a syntax error
 * in a {@code <script>} or an unknown property in a {@code <style>} - it is a server for
 * markup, and that is all it claims to be. The CSS server, handed the same style block on
 * its own, answers "Unknown property: 'colour'" at once. So the page is opened a second
 * time for each language inside it, as a copy with everything else blanked out, and each
 * copy goes to the server for that language.
 *
 * <p>The copies keep the page's own lines and columns, so what comes back needs no
 * translating: only the file name does, and {@link VirtualPaths} does that, which is what
 * puts the CSS server's complaint on the right line of the page.
 *
 * <p>Nothing is offered to install on account of a stretch of embedded code. Somebody
 * opening a page has asked for the HTML server; being asked to install two more because
 * the page has a script tag in it is not what they asked for. What is already there is
 * used, and what is not is quietly not used.
 */
final class VirtualDocuments {

    /** Long enough that typing a word is one update rather than five. */
    private static final Duration SETTLE = Duration.millis(300);

    /** One language inside the file, and the stand-in document it is checked through. */
    private static final class Copy {
        private final EmbeddedRegion region;
        private final LanguageSupport language;
        private final LspSession session;
        private final Path path;
        private boolean opened;
        private String sent;

        Copy(EmbeddedRegion region, LanguageSupport language, LspSession session, Path path) {
            this.region = region;
            this.language = language;
            this.session = session;
            this.path = path;
        }
    }

    private final Ide ide;
    private final CodeEditor editor;
    private final List<Copy> copies = new ArrayList<>();
    private final PauseTransition settle = new PauseTransition(SETTLE);
    private final Consumer<String> textListener = text -> settle.playFromStart();

    private VirtualDocuments(Ide ide, CodeEditor editor) {
        this.ide = ide;
        this.editor = editor;
        settle.setOnFinished(e -> push());
    }

    /**
     * One of these for an editor whose language has other languages inside it, when there
     * is a server for at least one of them; otherwise nothing.
     */
    static Optional<VirtualDocuments> forEditor(Ide ide, LspManager manager, CodeEditor editor) {
        List<EmbeddedRegion> regions = editor.language().embeddedRegions();
        if (regions.isEmpty()) {
            return Optional.empty();
        }
        VirtualDocuments documents = new VirtualDocuments(ide, editor);
        for (EmbeddedRegion region : regions) {
            Optional<LanguageSupport> inside = ide.languages().byId(region.languageId());
            if (inside.isEmpty()) {
                continue;
            }
            Optional<LspSession> session = manager.sessionForEmbedded(inside.get(), editor.workspace());
            if (session.isEmpty()) {
                continue;
            }
            documents.copies.add(new Copy(region, inside.get(), session.get(),
                    standIn(editor.path(), inside.get())));
        }
        return documents.copies.isEmpty() ? Optional.empty() : Optional.of(documents);
    }

    /**
     * The name the stand-in is opened under: the page's own name with the other
     * language's extension on the end, so {@code page.html} is also {@code page.html.css}.
     * No such file exists, and none is ever written; it is a name for the server to
     * answer about.
     */
    private static Path standIn(Path file, LanguageSupport inside) {
        String extension = inside.extensions().contains(inside.id())
                ? inside.id()
                : inside.extensions().stream().min(Comparator.comparingInt(String::length))
                        .orElse(inside.id());
        return file.resolveSibling(file.getFileName() + "." + extension);
    }

    void attach() {
        for (Copy copy : copies) {
            VirtualPaths.register(copy.path, editor.path());
        }
        editor.addTextListener(textListener);
        push();
    }

    /** Called when a server this was waiting for has finished starting. */
    void serverReady() {
        push();
    }

    private void push() {
        String page = editor.text();
        for (Copy copy : copies) {
            if (!copy.session.isReady()) {
                continue;
            }
            String text = VirtualText.of(page, copy.region.around());
            if (text == null) {
                // The page has no CSS in it any more - or never had. Take the copy back,
                // or the last complaint about it stays on screen for ever.
                close(copy);
                continue;
            }
            if (text.equals(copy.sent)) {
                continue;
            }
            copy.sent = text;
            if (copy.opened) {
                copy.session.didChange(copy.path, text);
            } else {
                copy.opened = true;
                copy.session.didOpen(copy.path, copy.language, text);
            }
        }
    }

    void detach() {
        settle.stop();
        editor.removeTextListener(textListener);
        for (Copy copy : copies) {
            close(copy);
            VirtualPaths.forget(copy.path);
        }
    }

    private void close(Copy copy) {
        if (copy.opened) {
            copy.opened = false;
            copy.sent = null;
            copy.session.didClose(copy.path);
        }
        // didClose clears the problems under the stand-in's name; the page is what they
        // were filed under, so it is the page that has to be cleared.
        ide.problems().set("lsp:" + copy.session.serverId(), editor.path(), List.of());
    }
}
