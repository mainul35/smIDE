package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.editor.CodeEditor;
import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The completion list under the caret. Filtered as you type; Enter or Tab applies the
 * item's edit (or its insert text), Escape closes.
 */
public final class CompletionPopup {

    private final Ide ide;
    private final CodeEditor editor;
    private final LspSession session;
    private final Popup popup = new Popup();
    private final ListView<CompletionItem> list = new ListView<>();
    private final Label doc = new Label();
    private final Runnable flush;
    private List<CompletionItem> all = List.of();
    private int anchor = -1;
    private int generation;

    CompletionPopup(Ide ide, CodeEditor editor, LspSession session, Runnable flush) {
        this.ide = ide;
        this.editor = editor;
        this.session = session;
        this.flush = flush;
        list.getStyleClass().add("popup-list");
        list.setPrefSize(460, 240);
        list.setCellFactory(v -> new ItemCell());
        list.setFocusTraversable(false);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                apply();
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> showDoc(item));
        doc.getStyleClass().add("completion-item-detail");
        doc.setWrapText(true);
        doc.setMaxWidth(460);
        VBox panel = new VBox(4, list, doc);
        panel.getStyleClass().add("popup-panel");
        // A Popup carries its own scene, so the application stylesheet and the dark class
        // have to be put on this subtree or the list renders in modena's default palette.
        panel.getStylesheets().add(ide.theme().stylesheet());
        ide.theme().style(panel);
        popup.getContent().add(panel);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public void hide() {
        popup.hide();
        all = List.of();
    }

    /** Asks the server; shows the list if anything comes back. */
    public void request(boolean explicit) {
        request(explicit, null);
    }

    /**
     * @param triggerCharacter the character that opened this, or null when the user asked
     *                         for completion outright. The protocol says a
     *                         TriggerCharacter request carries it, and a server that is
     *                         not told which character it was answers with everything in
     *                         scope instead of the members of what precedes the dot.
     */
    public void request(boolean explicit, String triggerCharacter) {
        if (!session.isReady() || session.capabilities() == null
                || session.capabilities().getCompletionProvider() == null) {
            if (explicit) {
                ide.statusBar().message("No completion available: " + session.displayName() + " is not ready.");
            }
            return;
        }
        // The debounce might still hold an unsent change; the server must see it first.
        flush.run();
        int gen = ++generation;
        int caret = editor.caretOffset();
        anchor = wordStart(caret);
        CompletionParams params = new CompletionParams(new TextDocumentIdentifier(Positions.uri(editor.path())),
                Positions.caret(editor));
        CompletionContext context = new CompletionContext(explicit || triggerCharacter == null
                ? CompletionTriggerKind.Invoked : CompletionTriggerKind.TriggerCharacter);
        if (context.getTriggerKind() == CompletionTriggerKind.TriggerCharacter) {
            context.setTriggerCharacter(triggerCharacter);
        }
        params.setContext(context);
        // Behind the pending edits, or the server answers for the text as it was.
        session.ordered(() -> session.server().getTextDocumentService().completion(params))
                .orTimeout(8, TimeUnit.SECONDS)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (gen != generation) {
                        return;
                    }
                    if (error != null) {
                        session.log("completion failed: " + error);
                        return;
                    }
                    List<CompletionItem> items = extract(result);
                    if (items.isEmpty()) {
                        if (explicit) {
                            ide.statusBar().message("No suggestions");
                        }
                        hide();
                        return;
                    }
                    all = items;
                    refilter();
                    if (!list.getItems().isEmpty()) {
                        show();
                    }
                }));
    }

    private static List<CompletionItem> extract(Either<List<CompletionItem>, CompletionList> result) {
        if (result == null) {
            return List.of();
        }
        List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();
        return items == null ? List.of() : items;
    }

    private int wordStart(int caret) {
        String text = editor.text();
        int start = caret;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        return start;
    }

    /** Applies the typed prefix to the last result set. */
    public void refilter() {
        if (all.isEmpty()) {
            return;
        }
        int caret = editor.caretOffset();
        if (anchor < 0 || caret < anchor) {
            hide();
            return;
        }
        String prefix = editor.text().substring(anchor, caret).toLowerCase(Locale.ROOT);
        List<CompletionItem> filtered = new ArrayList<>();
        for (CompletionItem item : all) {
            String key = (item.getFilterText() != null ? item.getFilterText() : item.getLabel()).toLowerCase(Locale.ROOT);
            if (prefix.isEmpty() || key.startsWith(prefix) || subsequence(prefix, key)) {
                filtered.add(item);
            }
        }
        filtered.sort(Comparator
                .comparing((CompletionItem i) -> !((i.getFilterText() != null ? i.getFilterText() : i.getLabel())
                        .toLowerCase(Locale.ROOT).startsWith(prefix)))
                .thenComparing(i -> i.getSortText() != null ? i.getSortText() : i.getLabel()));
        list.getItems().setAll(filtered.subList(0, Math.min(200, filtered.size())));
        if (filtered.isEmpty()) {
            hide();
        } else {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
    }

    private static boolean subsequence(String q, String text) {
        int qi = 0;
        for (int i = 0; i < text.length() && qi < q.length(); i++) {
            if (text.charAt(i) == q.charAt(qi)) {
                qi++;
            }
        }
        return qi == q.length();
    }

    private void show() {
        Bounds caret = editor.area().getCaretBounds().orElse(null);
        if (caret == null) {
            return;
        }
        popup.show(editor.area(), caret.getMinX(), caret.getMaxY() + 2);
    }

    private void showDoc(CompletionItem item) {
        if (item == null) {
            doc.setText("");
            return;
        }
        String detail = item.getDetail() == null ? "" : item.getDetail();
        String documentation = "";
        if (item.getDocumentation() != null) {
            documentation = item.getDocumentation().isLeft() ? item.getDocumentation().getLeft()
                    : item.getDocumentation().getRight().getValue();
        }
        String text = (detail + "\n" + HoverPopup.plain(documentation)).strip();
        doc.setText(text.length() > 600 ? text.substring(0, 600) + "…" : text);
    }

    /** Key handling while showing: navigation, apply, dismiss. True if consumed. */
    public boolean handleKey(KeyEvent e) {
        switch (e.getCode()) {
            case DOWN -> {
                move(1);
                return true;
            }
            case UP -> {
                move(-1);
                return true;
            }
            case PAGE_DOWN -> {
                move(8);
                return true;
            }
            case PAGE_UP -> {
                move(-8);
                return true;
            }
            case ENTER, TAB -> {
                apply();
                return true;
            }
            case ESCAPE -> {
                hide();
                return true;
            }
            case BACK_SPACE -> {
                Platform.runLater(this::refilter);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(size - 1, idx + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(Math.max(0, next - 3));
    }

    private void apply() {
        CompletionItem item = list.getSelectionModel().getSelectedItem();
        hide();
        if (item == null) {
            return;
        }
        int caret = editor.caretOffset();
        String insert = item.getInsertText() != null ? item.getInsertText() : item.getLabel();
        int start = anchor >= 0 ? anchor : wordStart(caret);
        int end = caret;
        if (item.getTextEdit() != null && item.getTextEdit().isLeft()) {
            TextEdit edit = item.getTextEdit().getLeft();
            int[] range = Positions.offsets(editor, edit.getRange());
            start = range[0];
            end = Math.max(range[1], caret);
            insert = edit.getNewText();
        }
        if (item.getInsertTextFormat() == InsertTextFormat.Snippet) {
            insert = stripSnippet(insert);
        }
        int cursorOffset = insert.indexOf('\0');
        if (cursorOffset >= 0) {
            insert = insert.replace("\0", "");
        }
        editor.replace(start, end, insert);
        int newCaret = start + (cursorOffset >= 0 ? cursorOffset : insert.length());
        editor.moveCaret(editor.lineOf(newCaret), editor.columnOf(newCaret));
        if (item.getAdditionalTextEdits() != null && !item.getAdditionalTextEdits().isEmpty()) {
            List<TextEdit> extra = new ArrayList<>(item.getAdditionalTextEdits());
            extra.sort(Comparator.comparing((TextEdit t) -> t.getRange().getStart().getLine())
                    .thenComparing(t -> t.getRange().getStart().getCharacter()).reversed());
            int keep = editor.caretOffset();
            int before = editor.text().length();
            WorkspaceEdits.apply(editor, extra);
            int delta = editor.text().length() - before;
            int restored = Math.max(0, Math.min(editor.text().length(), keep + delta));
            editor.moveCaret(editor.lineOf(restored), editor.columnOf(restored));
        }
        if (item.getCommand() != null && session.isReady()) {
            session.server().getWorkspaceService().executeCommand(
                    new org.eclipse.lsp4j.ExecuteCommandParams(item.getCommand().getCommand(), item.getCommand().getArguments()));
        }
    }

    /**
     * Snippet syntax reduced to plain text: {@code ${1:name}} becomes {@code name}, {@code $0}
     * marks where the caret goes (encoded as NUL), other tab stops vanish.
     */
    static String stripSnippet(String snippet) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean cursorSet = false;
        while (i < snippet.length()) {
            char c = snippet.charAt(i);
            if (c == '\\' && i + 1 < snippet.length()) {
                out.append(snippet.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '$') {
                if (i + 1 < snippet.length() && snippet.charAt(i + 1) == '{') {
                    int close = matchingBrace(snippet, i + 1);
                    String body = snippet.substring(i + 2, close);
                    int colon = body.indexOf(':');
                    int pipe = body.indexOf('|');
                    if (colon >= 0) {
                        out.append(stripSnippet(body.substring(colon + 1)));
                    } else if (pipe >= 0) {
                        String choices = body.substring(pipe + 1, body.lastIndexOf('|'));
                        out.append(choices.split(",")[0]);
                    } else if (body.equals("0") && !cursorSet) {
                        out.append('\0');
                        cursorSet = true;
                    }
                    i = close + 1;
                    continue;
                }
                int j = i + 1;
                while (j < snippet.length() && Character.isDigit(snippet.charAt(j))) {
                    j++;
                }
                if (j > i + 1) {
                    if (snippet.substring(i + 1, j).equals("0") && !cursorSet) {
                        out.append('\0');
                        cursorSet = true;
                    }
                    i = j;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') {
                depth++;
            } else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length() - 1;
    }

    private static final class ItemCell extends ListCell<CompletionItem> {
        @Override
        protected void updateItem(CompletionItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label label = new Label(item.getLabel());
            Label detail = new Label(item.getDetail() == null ? "" : item.getDetail());
            detail.getStyleClass().add("completion-item-detail");
            detail.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(detail, Priority.ALWAYS);
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            var icon = Icons.of(iconFor(item.getKind()), 13);
            if (icon != null) {
                row.getChildren().add(icon);
            }
            row.getChildren().addAll(label, detail);
            setText(null);
            setGraphic(row);
        }

        private static String iconFor(CompletionItemKind kind) {
            if (kind == null) {
                return "fth-circle";
            }
            return switch (kind) {
                case Method, Function, Constructor -> "fth-zap";
                case Field, Property, Variable, Constant -> "fth-box";
                case Class, Interface, Struct, Enum, TypeParameter -> "fth-layers";
                case Module, Unit, File, Folder -> "fth-package";
                case Keyword -> "fth-key";
                case Snippet -> "fth-code";
                default -> "fth-circle";
            };
        }
    }
}
