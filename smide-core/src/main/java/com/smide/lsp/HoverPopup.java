package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.editor.CodeEditor;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Documentation for the symbol under the pointer or caret, as a small popup. */
public final class HoverPopup {

    private final Ide ide;
    private final CodeEditor editor;
    private final LspSession session;
    private final Popup popup = new Popup();
    private final Label label = new Label();
    private int generation;

    HoverPopup(Ide ide, CodeEditor editor, LspSession session) {
        this.ide = ide;
        this.editor = editor;
        this.session = session;
        label.setWrapText(true);
        label.setMaxWidth(520);
        label.getStyleClass().add("code");
        /* Javadoc for a class like SpringApplication runs to pages. Unbounded, the popup
           grew past the bottom of the screen and covered the editor it was documenting. */
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(label);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(320);
        scroll.setPrefViewportWidth(520);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("doc-popup-scroll");
        VBox box = new VBox(scroll);
        box.setMaxHeight(340);
        box.getStyleClass().add("doc-popup");
        box.getStylesheets().add(ide.theme().stylesheet());
        ide.theme().style(box);
        popup.getContent().add(box);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
    }

    public void hide() {
        generation++;
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    /** Shows documentation for the caret, as Quick Documentation does. */
    public void showAtCaret() {
        showAt(editor.caretOffset());
    }

    public void showAt(int offset) {
        if (!session.isReady() || session.capabilities() == null || session.capabilities().getHoverProvider() == null) {
            return;
        }
        int gen = ++generation;
        HoverParams params = new HoverParams(new TextDocumentIdentifier(Positions.uri(editor.path())),
                Positions.of(editor, offset));
        session.server().getTextDocumentService().hover(params)
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((hover, error) -> Platform.runLater(() -> {
                    if (gen != generation || error != null || hover == null) {
                        return;
                    }
                    String text = contents(hover);
                    if (text.isBlank()) {
                        return;
                    }
                    label.setText(text.length() > 4000 ? text.substring(0, 4000) + "…" : text);
                    Bounds bounds = editor.area().getCharacterBoundsOnScreen(offset, Math.min(offset + 1, editor.text().length()))
                            .orElse(null);
                    if (bounds == null) {
                        bounds = editor.area().getCaretBounds().orElse(null);
                        if (bounds == null) {
                            return;
                        }
                    }
                    popup.show(editor.area(), bounds.getMinX(), bounds.getMaxY() + 4);
                }));
    }

    private static String contents(Hover hover) {
        Either<List<Either<String, MarkedString>>, MarkupContent> c = hover.getContents();
        if (c == null) {
            return "";
        }
        if (c.isRight()) {
            return plain(c.getRight().getValue());
        }
        StringBuilder sb = new StringBuilder();
        for (Either<String, MarkedString> part : c.getLeft()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(part.isLeft() ? plain(part.getLeft()) : part.getRight().getValue());
        }
        return sb.toString();
    }

    /** Markdown reduced to readable text: fences and inline markers dropped, links to their text. */
    public static String plain(String markdown) {
        if (markdown == null) {
            return "";
        }
        String s = markdown.replace("\r\n", "\n");
        s = s.replaceAll("(?m)^```[a-zA-Z0-9_-]*\\s*$", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");
        s = s.replace("**", "").replace("__", "");
        s = s.replaceAll("(?<!`)`([^`\\n]+)`(?!`)", "$1");
        s = s.replaceAll("&nbsp;", " ").replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }
}
