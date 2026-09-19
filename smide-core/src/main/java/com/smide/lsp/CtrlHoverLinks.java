package com.smide.lsp;

import com.smide.api.editor.DeclarationProvider;
import com.smide.core.IdeImpl;
import com.smide.editor.CodeEditor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Ctrl+hover: what Ctrl+click would follow is drawn as a link while Ctrl is held over it.
 *
 * <p>IntelliJ does this so that a name can be seen to be followable before it is clicked, and
 * so that a click on something that leads nowhere is not a surprise. Two sources say what
 * leads somewhere. A plugin that knows a kind of file answers at once - a dependency in a pom
 * is a link when its pom is there, and never when it is missing and drawn red. For everything
 * else the language server is asked where the name is declared, and the name becomes a link
 * only when it has an answer - after a moment's pause, so that moving the pointer across a
 * line does not ask about every word on it, and the answer for a word is kept while the
 * pointer stays on it.
 */
public final class CtrlHoverLinks {

    private static final Duration ASK_AFTER = Duration.millis(120);

    private final Supplier<List<DeclarationProvider>> providers;
    private final Supplier<LspManager> lsp;
    private final CodeEditor editor;
    private final PauseTransition ask = new PauseTransition(ASK_AFTER);

    private double x;
    private double y;
    private boolean shortcut;
    private boolean over;
    /** Bumped on every change that makes an answer in flight out of date. */
    private int generation;
    /** The word last asked about, and the answer, until the text changes. */
    private int[] askedWord;
    private int[] askedLink;
    private boolean askedAnswered;

    private CtrlHoverLinks(Supplier<List<DeclarationProvider>> providers, Supplier<LspManager> lsp, CodeEditor editor) {
        this.providers = providers;
        this.lsp = lsp;
        this.editor = editor;
    }

    public static void install(IdeImpl ide, Supplier<LspManager> lsp, CodeEditor editor) {
        install(() -> ide.registry().declarationProviders(), lsp, editor);
    }

    /** With the providers given directly: for an editor outside the IDE, and for testing. */
    public static void install(Supplier<List<DeclarationProvider>> providers, Supplier<LspManager> lsp, CodeEditor editor) {
        new CtrlHoverLinks(providers, lsp, editor).attach();
    }

    private void attach() {
        editor.area().addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            x = e.getX();
            y = e.getY();
            over = true;
            shortcut = e.isShortcutDown();
            update();
        });
        editor.area().addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            over = false;
            clear();
        });
        editor.area().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isShortcutKey(e.getCode())) {
                shortcut = true;
                update();
            }
        });
        editor.area().addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (isShortcutKey(e.getCode())) {
                shortcut = false;
                clear();
            }
        });
        editor.area().focusedProperty().addListener((property, was, now) -> {
            if (!now) {
                // Ctrl released in another window would never be heard here.
                shortcut = false;
                clear();
            }
        });
        editor.addTextListener(text -> {
            askedWord = null;
            clear();
        });
    }

    private static boolean isShortcutKey(KeyCode code) {
        return code == KeyCode.CONTROL || code == KeyCode.META;
    }

    private void update() {
        if (!shortcut || !over) {
            clear();
            return;
        }
        int offset = editor.characterAt(x, y);
        if (offset < 0) {
            clear();
            return;
        }
        int[] shown = editor.link();
        if (shown != null && offset >= shown[0] && offset < shown[1]) {
            return;
        }
        String text = editor.text();
        for (DeclarationProvider provider : providers.get()) {
            Optional<DeclarationProvider.Span> span;
            try {
                span = provider.linkAt(editor.path(), text, offset);
            } catch (RuntimeException e) {
                continue;
            }
            if (span.isPresent()) {
                generation++;
                editor.showLink(span.get().start(), span.get().end());
                return;
            }
        }
        int[] word = LspActions.wordAt(text, offset);
        if (word == null) {
            clear();
            return;
        }
        if (askedAnswered && askedWord != null && askedWord[0] == word[0] && askedWord[1] == word[1]) {
            show(askedLink);
            return;
        }
        LspManager manager = lsp.get();
        if (manager == null || manager.bindingOf(editor).isEmpty()) {
            clear();
            return;
        }
        clear();
        int asking = ++generation;
        ask.setOnFinished(e -> LspActions.linkAt(manager, editor, offset).thenAccept(found ->
                Platform.runLater(() -> {
                    askedWord = word;
                    askedLink = found.orElse(null);
                    askedAnswered = true;
                    if (asking == generation && shortcut && over) {
                        show(askedLink);
                    }
                })));
        ask.playFromStart();
    }

    private void show(int[] span) {
        if (span == null) {
            editor.clearLink();
        } else {
            editor.showLink(span[0], span[1]);
        }
    }

    private void clear() {
        generation++;
        ask.stop();
        editor.clearLink();
    }
}
