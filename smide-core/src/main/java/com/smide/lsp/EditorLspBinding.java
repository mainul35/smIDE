package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.editor.CodeEditor;
import javafx.animation.PauseTransition;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps one editor in step with its language server: document sync, completion on
 * typing, hover on the pointer, and Ctrl+click navigation.
 */
public final class EditorLspBinding {

    private final Ide ide;
    private final LspManager manager;
    private final CodeEditor editor;
    private final LspSession session;
    private final PauseTransition changeDebounce = new PauseTransition(Duration.millis(150));
    private final PauseTransition completionDelay = new PauseTransition(Duration.millis(220));
    private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(900));
    private final CompletionPopup completion;
    private final HoverPopup hover;
    private final Consumer<String> textListener;
    private final javafx.event.EventHandler<KeyEvent> keyHandler;
    private final javafx.event.EventHandler<KeyEvent> keyTyped;
    private final javafx.event.EventHandler<MouseEvent> mouseMoved;
    private final javafx.event.EventHandler<MouseEvent> exited;
    private final javafx.event.EventHandler<MouseEvent> entered;
    /** A moment's grace on the way out, so the pointer can reach the popup. */
    private final PauseTransition hoverLeave = new PauseTransition(Duration.millis(250));
    private final javafx.event.EventHandler<MouseEvent> mousePressed;
    private boolean opened;
    private String lastTyped = "";
    private int hoverOffset = -1;

    EditorLspBinding(Ide ide, LspManager manager, CodeEditor editor, LspSession session) {
        this.ide = ide;
        this.manager = manager;
        this.editor = editor;
        this.session = session;
        this.completion = new CompletionPopup(ide, editor, session, this::flush);
        this.hover = new HoverPopup(ide, editor, session);
        this.textListener = text -> changeDebounce.playFromStart();
        changeDebounce.setOnFinished(e -> {
            if (opened) {
                session.didChange(editor.path(), editor.text());
            }
        });
        completionDelay.setOnFinished(e -> completion.request(false));
        hoverDelay.setOnFinished(e -> {
            if (hoverOffset >= 0) {
                hover.showAt(hoverOffset);
            }
        });
        this.keyHandler = this::onKeyPressed;
        this.keyTyped = this::onKeyTyped;
        this.mouseMoved = this::onMouseMoved;
        hoverLeave.setOnFinished(e -> {
            if (!hover.isPointerOver()) {
                hover.hide();
            }
        });
        this.exited = e -> {
            hoverDelay.stop();
            hoverOffset = -1;
            hoverLeave.playFromStart();
        };
        this.entered = e -> hoverLeave.stop();
        this.mousePressed = this::onMousePressed;
    }

    public LspSession session() {
        return session;
    }

    public CodeEditor editor() {
        return editor;
    }

    public CompletionPopup completion() {
        return completion;
    }

    public HoverPopup hover() {
        return hover;
    }

    void attach() {
        editor.addTextListener(textListener);
        CodeArea area = editor.area();
        area.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        area.addEventHandler(KeyEvent.KEY_TYPED, keyTyped);
        area.addEventHandler(MouseEvent.MOUSE_MOVED, mouseMoved);
        /* The popup appears under the pointer, so the next mouse move goes to the popup
           and never to the editor: without this the documentation stayed on screen,
           covering the code, until something else closed it. Leaving is what closes it -
           unless the pointer has landed on the popup, which is someone reading it. */
        area.addEventHandler(MouseEvent.MOUSE_EXITED, exited);
        area.addEventHandler(MouseEvent.MOUSE_ENTERED, entered);
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, mousePressed);
        if (session.isReady()) {
            serverReady();
        }
    }

    void serverReady() {
        if (!opened && session.isReady()) {
            opened = true;
            session.didOpen(editor.path(), editor.language(), editor.text());
        }
    }

    void saved() {
        if (opened) {
            changeDebounce.stop();
            session.didChange(editor.path(), editor.text());
            session.didSave(editor.path(), editor.text());
        }
    }

    void detach() {
        editor.removeTextListener(textListener);
        CodeArea area = editor.area();
        area.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        area.removeEventHandler(KeyEvent.KEY_TYPED, keyTyped);
        area.removeEventHandler(MouseEvent.MOUSE_MOVED, mouseMoved);
        area.removeEventHandler(MouseEvent.MOUSE_EXITED, exited);
        area.removeEventHandler(MouseEvent.MOUSE_ENTERED, entered);
        area.removeEventFilter(MouseEvent.MOUSE_PRESSED, mousePressed);
        completion.hide();
        hover.hide();
        if (opened) {
            session.didClose(editor.path());
            opened = false;
        }
    }

    /** Makes sure the server has the latest text before a request. */
    public void flush() {
        if (changeDebounce.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            changeDebounce.stop();
            if (opened) {
                session.didChange(editor.path(), editor.text());
            }
        }
    }

    private void onKeyPressed(KeyEvent e) {
        if (completion.isShowing() && completion.handleKey(e)) {
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown()) {
            e.consume();
            if (editor.area().isEditable()) {
                completion.request(true);
            }
        } else if (e.getCode() == KeyCode.ESCAPE) {
            hover.hide();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || e.isControlDown() || e.isAltDown()) {
            return;
        }
        if (!editor.area().isEditable()) {
            // Library source: nothing can be typed here, so nothing should be suggested.
            return;
        }
        char c = ch.charAt(0);
        hover.hide();
        if (Character.isLetterOrDigit(c) || c == '_') {
            lastTyped += c;
            if (completion.isShowing()) {
                completion.refilter();
            } else if (lastTyped.length() >= 1 && ide.settings().getBoolean("editor.autoPopup", true)) {
                completionDelay.playFromStart();
            }
        } else {
            lastTyped = "";
            if (isTriggerCharacter(c)) {
                completion.hide();
                completionDelay.playFromStart();
            } else if (completion.isShowing()) {
                completion.hide();
            }
        }
    }

    private boolean isTriggerCharacter(char c) {
        if (session.capabilities() == null || session.capabilities().getCompletionProvider() == null) {
            return c == '.';
        }
        List<String> triggers = session.capabilities().getCompletionProvider().getTriggerCharacters();
        if (triggers == null) {
            return c == '.';
        }
        for (String t : triggers) {
            if (t.length() == 1 && t.charAt(0) == c) {
                return true;
            }
        }
        return false;
    }

    private void onMouseMoved(MouseEvent e) {
        var hit = editor.area().hit(e.getX(), e.getY());
        int offset = hit.getCharacterIndex().orElse(-1);
        if (offset != hoverOffset) {
            hoverOffset = offset;
            hover.hide();
            /* Not while Ctrl is held: that is someone lining up a Ctrl+click, and a
               documentation popup appearing under the pointer is exactly what they do
               not want on the way to a declaration. */
            if (offset >= 0 && !e.isControlDown() && ide.settings().getBoolean("editor.hoverDocs", true)) {
                hoverDelay.playFromStart();
            }
        }
    }

    /**
     * Ctrl+click goes to the declaration.
     *
     * <p>On the press rather than the click: the editor moves the caret and may start a
     * selection on press, and a click event is not delivered at all if the pointer moves
     * a pixel between press and release. Consuming it here also stops the modifier-click
     * from selecting anything.
     */
    private void onMousePressed(MouseEvent e) {
        // Any press dismisses documentation: it is a hint about what is under the
        // pointer, and the pointer is about to do something else.
        hoverDelay.stop();
        hover.hide();
        if (e.getButton() != MouseButton.PRIMARY || !e.isControlDown() || e.isShiftDown()) {
            return;
        }
        e.consume();
        var hit = editor.area().hit(e.getX(), e.getY());
        int offset = hit.getInsertionIndex();
        if (offset < 0) {
            return;
        }
        editor.moveCaret(editor.lineOf(offset), editor.columnOf(offset));
        editor.area().requestFocus();
        LspActions.gotoDefinition(ide, manager, editor);
    }
}
