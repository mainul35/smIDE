package com.smide.plugins.markdown;

import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;
import com.smide.api.Ide;
import com.smide.api.editor.TextEditor;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.Token;
import com.smide.api.problems.Diagnostic;
import com.smide.api.util.EventBus;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.TwoDimensional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown editor: a RichTextFX {@link CodeArea} for the source, MDViewer's preview
 * beside it, and a mode bar to show one or both. The raw side mirrors the core's editor
 * (line numbers, background highlighting, undo-based modified tracking, line-separator
 * detection); the preview side re-renders 200 ms after the last edit, pushing PlantUML
 * diagrams in as they are drawn.
 *
 * <p>Text is normalised to {@code \n} inside; the file's own line separator is
 * remembered and written back on save.
 */
public final class MarkdownEditor implements TextEditor {

    /** Which panes are showing; persisted under {@code markdown.mode}. */
    public enum Mode {
        RAW, SPLIT, PREVIEW;

        static Mode parse(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return SPLIT;
            }
        }
    }

    static final String MODE_KEY = "markdown.mode";
    private static final int HIGHLIGHT_LIMIT = 2_000_000;
    private static final Duration PREVIEW_DELAY = Duration.millis(200);
    private static final KeyCombination FIND = KeyCombination.keyCombination("shortcut+F");
    /** Indent, list marker, gap, and an optional task box: what Enter continues. */
    private static final Pattern LIST_ITEM =
            Pattern.compile("^([ \\t]*)([-*+]|\\d{1,9})([.)]?)([ \\t]+)(\\[[ xX]\\][ \\t]+)?(.*)$");

    private static final ExecutorService HIGHLIGHTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smide-markdown-highlighter");
        t.setDaemon(true);
        return t;
    });

    private final Ide ide;
    private final Workspace workspace;
    private final LanguageSupport language;
    private final MarkdownService markdownService;

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
    /** Built when this editor first reaches the screen; null until then. See {@link #preview()}. */
    private MarkdownPreview preview;
    private final DiagramService diagramService;
    private final SplitPane split = new SplitPane();
    private final BorderPane root = new BorderPane();
    private final MarkdownFindBar findBar;
    private final ToggleButton rawButton = new ToggleButton("Raw");
    private final ToggleButton splitButton = new ToggleButton("Split");
    private final ToggleButton previewButton = new ToggleButton("Preview");
    private final PauseTransition previewDebounce = new PauseTransition(PREVIEW_DELAY);
    private final EventBus.Subscription themeSubscription;

    private final ReadOnlyBooleanWrapper modified = new ReadOnlyBooleanWrapper(false);
    private final List<Consumer<String>> textListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> caretListeners = new CopyOnWriteArrayList<>();

    private Path path;
    private String lineSeparator = System.lineSeparator();
    private FileTime loadedTime;
    private List<Token> tokens = List.of();
    private List<Diagnostic> diagnostics = List.of();
    private List<Spans.Overlay> searchHits = List.of();
    private int highlightGeneration;
    private Mode mode;
    private boolean previewStale = true;
    /** Whether this editor's tab has ever been the one on show. See {@link #shown()}. */
    private boolean seen;
    private boolean disposed;

    public MarkdownEditor(Ide ide, Workspace workspace, Path path, LanguageSupport language,
                          MarkdownService markdownService, DiagramService diagramService) {
        this.ide = ide;
        this.workspace = workspace;
        this.path = path.toAbsolutePath().normalize();
        this.language = language;
        this.markdownService = markdownService;

        area.getStyleClass().add("code-area");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setLineHighlighterOn(false);
        area.setWrapText(ide.settings().getBoolean("editor.wrap", true));
        String family = ide.settings().get("editor.fontFamily", "Cascadia Code").replace("\"", "");
        int size = ide.settings().getInt("editor.fontSize", 13);
        area.setStyle("-fx-font-family: \"" + family + "\", \"Cascadia Mono\", Consolas, monospace;"
                + " -fx-font-size: " + size + "px;");

        this.diagramService = diagramService;
        themeSubscription = ide.events().subscribe(Events.ThemeChanged.class, e -> {
            if (preview != null) {
                preview.setDark(e.dark());
            }
        });


        findBar = new MarkdownFindBar(this, area);
        root.getStyleClass().add("code-editor");
        root.setTop(new VBox(modeBar(), findBar));
        split.setOrientation(Orientation.HORIZONTAL);

        load();

        area.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis(120))
                .subscribe(ignore -> {
                    scheduleHighlight();
                    String text = area.getText();
                    textListeners.forEach(l -> l.accept(text));
                });
        area.plainTextChanges().subscribe(ignore -> {
            previewStale = true;
            if (mode != Mode.RAW) {
                previewDebounce.playFromStart();
            }
        });
        previewDebounce.setOnFinished(e -> renderPreview());
        area.caretPositionProperty().addListener((obs, was, now) -> caretListeners.forEach(Runnable::run));
        area.getUndoManager().atMarkedPositionProperty().addListener((obs, was, now) -> modified.set(!now));
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);

        setMode(Mode.parse(ide.settings().get(MODE_KEY, "split")));
    }

    // ------------------------------------------------------------------- modes

    private HBox modeBar() {
        ToggleGroup group = new ToggleGroup();
        rawButton.setToggleGroup(group);
        splitButton.setToggleGroup(group);
        previewButton.setToggleGroup(group);
        rawButton.setTooltip(com.smide.api.ui.Tooltips.of("Source only"));
        splitButton.setTooltip(com.smide.api.ui.Tooltips.of("Source and preview"));
        previewButton.setTooltip(com.smide.api.ui.Tooltips.of("Preview only"));
        rawButton.setOnAction(e -> setMode(Mode.RAW));
        splitButton.setOnAction(e -> setMode(Mode.SPLIT));
        previewButton.setOnAction(e -> setMode(Mode.PREVIEW));
        for (ToggleButton b : List.of(rawButton, splitButton, previewButton)) {
            b.setFocusTraversable(false);
        }
        HBox buttons = new HBox(2, rawButton, splitButton, previewButton);
        buttons.getStyleClass().add("popup-tabs");
        HBox bar = new HBox(buttons);
        bar.getStyleClass().add("tool-window-header");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    public Mode mode() {
        return mode;
    }

    /** Switches panes and remembers the choice for the next Markdown file. */
    public void setMode(Mode newMode) {
        if (newMode == null || disposed) {
            return;
        }
        boolean changed = mode != newMode;
        mode = newMode;
        (switch (mode) {
            case RAW -> rawButton;
            case SPLIT -> splitButton;
            case PREVIEW -> previewButton;
        }).setSelected(true);

        // Take both panes out of wherever they are before placing them again.
        split.getItems().clear();
        root.setCenter(null);
        boolean canPreview = mode != Mode.RAW && seen;
        switch (mode) {
            case RAW -> root.setCenter(scroll);
            // Until this editor is on screen the source stands alone: see preview().
            case PREVIEW -> root.setCenter(canPreview ? preview().node() : scroll);
            case SPLIT -> {
                if (canPreview) {
                    split.getItems().addAll(scroll, preview().node());
                    split.setDividerPositions(0.5);
                    root.setCenter(split);
                } else {
                    root.setCenter(scroll);
                }
            }
        }
        if (changed) {
            ide.settings().set(MODE_KEY, mode.name().toLowerCase(Locale.ROOT));
        }
        if (mode != Mode.RAW && previewStale) {
            previewDebounce.stop();
            renderPreview();
        }
    }

    // ----------------------------------------------------------------- preview

    /**
     * The preview pane, built the first time it is going to be seen.
     *
     * <p>It is a WebView, and a WebView is the most expensive thing this editor builds: a
     * second and a half of the ten seconds a ten-file session took to come back was
     * Markdown tabs building previews of files nobody had opened yet. A restored tab that
     * is never clicked never builds one.
     */
    private MarkdownPreview preview() {
        if (preview == null) {
            preview = new MarkdownPreview(diagramService, ide.theme().isDark(), this::openLocalLink,
                    url -> ide.window().browse(url));
        }
        return preview;
    }

    /**
     * Renders on the FX thread ({@link MarkdownService} is fast) and hands the result to
     * the preview, which fills PlantUML placeholders asynchronously.
     */
    private void renderPreview() {
        if (disposed || mode == Mode.RAW || preview == null) {
            // Nothing to render into yet; building it renders what is current.
            return;
        }
        previewStale = false;
        preview.show(markdownService.render(area.getText(), path.getParent()));
    }

    MarkdownService.Result renderNow() {
        return markdownService.render(area.getText(), path.getParent());
    }

    private void openLocalLink(Path target) {
        if (!MarkdownPreview.exists(target)) {
            ide.notifications().warn("Linked file not found", target.toString());
            return;
        }
        ide.editors().open(target);
    }

    // ----------------------------------------------------------------- loading

    private void load() {
        String content = "";
        try {
            byte[] bytes = Files.readAllBytes(path);
            content = new String(bytes, StandardCharsets.UTF_8);
            loadedTime = Files.getLastModifiedTime(path);
        } catch (IOException e) {
            System.err.println("smIDE markdown: cannot read " + path + ": " + e);
        }
        if (content.contains("\r\n")) {
            lineSeparator = "\r\n";
            content = content.replace("\r\n", "\n");
        } else if (content.contains("\n")) {
            lineSeparator = "\n";
        }
        area.replaceText(content);
        area.getUndoManager().forgetHistory();
        area.getUndoManager().mark();
        modified.set(false);
        area.moveTo(0);
        previewStale = true;
        scheduleHighlight();
    }

    @Override
    public void reload() {
        int caret = area.getCaretPosition();
        load();
        area.moveTo(Math.min(caret, area.getLength()));
        if (mode != Mode.RAW) {
            renderPreview();
        }
    }

    /** True if the file on disk is newer than what was loaded. */
    public boolean isStaleOnDisk() {
        try {
            FileTime now = Files.getLastModifiedTime(path);
            return loadedTime != null && now.compareTo(loadedTime) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void save() {
        write(path);
    }

    @Override
    public void saveAs(Path target) {
        this.path = target.toAbsolutePath().normalize();
        write(this.path);
        previewStale = true; // Relative images now resolve against the new folder.
        if (mode != Mode.RAW) {
            renderPreview();
        }
    }

    private void write(Path target) {
        String text = area.getText().replace("\n", lineSeparator);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
            loadedTime = Files.getLastModifiedTime(target);
            area.getUndoManager().mark();
            modified.set(false);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + target + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ highlighting

    private void scheduleHighlight() {
        if (disposed) {
            return;
        }
        String text = area.getText();
        int generation = ++highlightGeneration;
        Highlighter highlighter = language.highlighter();
        if (text.length() > HIGHLIGHT_LIMIT || highlighter == Highlighter.NONE) {
            tokens = List.of();
            applyStyles();
            return;
        }
        HIGHLIGHTER.execute(() -> {
            List<Token> result;
            try {
                result = highlighter.tokenize(text);
            } catch (RuntimeException e) {
                System.err.println("smIDE markdown: highlighter failed: " + e);
                result = List.of();
            }
            List<Token> finalResult = result;
            Platform.runLater(() -> {
                if (generation == highlightGeneration && !disposed) {
                    tokens = finalResult;
                    applyStyles();
                }
            });
        });
    }

    private void applyStyles() {
        int length = area.getLength();
        List<Spans.Overlay> overlays = new ArrayList<>(searchHits);
        for (Diagnostic d : diagnostics) {
            int start = clampOffset(offsetOf(d.startLine(), d.startColumn()));
            int end = clampOffset(offsetOf(d.endLine(), d.endColumn()));
            if (end <= start) {
                end = Math.min(length, start + 1);
            }
            if (end > start) {
                overlays.add(new Spans.Overlay(start, end, "diag-" + d.severity().name().toLowerCase(Locale.ROOT)));
            }
        }
        try {
            area.setStyleSpans(0, Spans.of(length, tokens, overlays));
        } catch (RuntimeException e) {
            // Text changed under us; the next pass repaints.
        }
    }

    private int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, area.getLength()));
    }

    void setSearchHits(List<Spans.Overlay> hits) {
        this.searchHits = hits;
        applyStyles();
    }

    // ------------------------------------------------------------ key handling

    private void onKeyPressed(KeyEvent e) {
        if (FIND.match(e)) {
            e.consume();
            showFind();
        } else if (e.getCode() == KeyCode.ESCAPE && findBar.isVisible()) {
            e.consume();
            findBar.hideBar();
        } else if (e.getCode() == KeyCode.ENTER && !e.isControlDown() && !e.isShiftDown()) {
            e.consume();
            insertNewline();
        } else if (e.getCode() == KeyCode.TAB) {
            e.consume();
            if (e.isShiftDown()) {
                unindentSelection();
            } else if (area.getSelection().getLength() > 0 && area.getSelectedText().contains("\n")) {
                indentSelection();
            } else {
                area.insertText(area.getCaretPosition(), indentUnit());
            }
        } else if (e.getCode() == KeyCode.BACK_SPACE && !e.isControlDown() && area.getSelection().getLength() == 0) {
            int caret = area.getCaretPosition();
            if (caret > 0 && caret < area.getLength()) {
                char before = area.getText(caret - 1, caret).charAt(0);
                char after = area.getText(caret, caret + 1).charAt(0);
                if (isPair(before, after)) {
                    e.consume();
                    area.deleteText(caret - 1, caret + 1);
                }
            }
        }
    }

    /** Brackets from the language's pairs close themselves and closers are stepped over. */
    private void onKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (ch == null || ch.length() != 1 || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        char c = ch.charAt(0);
        String pairs = language.bracketPairs();
        int idx = pairs.indexOf(c);
        if (idx < 0) {
            return;
        }
        int caret = area.getCaretPosition();
        char next = caret < area.getLength() ? area.getText(caret, caret + 1).charAt(0) : '\n';
        if (idx % 2 == 1) {
            if (next == c) {
                e.consume();
                area.moveTo(caret + 1);
            }
            return;
        }
        if (Character.isWhitespace(next) || ")]".indexOf(next) >= 0 || next == '.' || next == ',') {
            e.consume();
            char closer = pairs.charAt(idx + 1);
            String selected = area.getSelectedText();
            area.replaceSelection(c + selected + closer);
            area.moveTo(caret + 1 + selected.length());
            if (!selected.isEmpty()) {
                area.selectRange(caret + 1, caret + 1 + selected.length());
            }
        }
    }

    private boolean isPair(char open, char close) {
        String pairs = language.bracketPairs();
        int idx = pairs.indexOf(open);
        return idx >= 0 && idx % 2 == 0 && pairs.charAt(idx + 1) == close;
    }

    /**
     * Enter keeps the indent and continues a list: {@code - } and {@code 3. } carry on
     * (numbers advancing), a task box comes along unticked, and Enter on an empty item
     * ends the list by clearing the marker instead.
     */
    private void insertNewline() {
        int par = area.getCurrentParagraph();
        String line = area.getParagraph(par).getText();
        int col = area.getCaretColumn();
        String before = line.substring(0, Math.min(col, line.length()));
        Matcher m = LIST_ITEM.matcher(line);
        if (m.matches() && col >= line.length() - m.group(6).length()) {
            if (m.group(6).isEmpty()) {
                // Empty item: end the list.
                int start = area.getAbsolutePosition(par, 0);
                area.replaceText(start, start + line.length(), m.group(1));
                return;
            }
            String marker = m.group(2);
            if (Character.isDigit(marker.charAt(0))) {
                marker = String.valueOf(Long.parseLong(marker) + 1);
            }
            String box = m.group(5) == null ? "" : "[ ] ";
            area.replaceSelection("\n" + m.group(1) + marker + m.group(3) + m.group(4) + box);
            return;
        }
        area.replaceSelection("\n" + leadingWhitespace(before));
    }

    private String indentUnit() {
        return language.useTabs() ? "\t" : " ".repeat(Math.max(1, language.indentSize()));
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private IndexRange selectedParagraphs() {
        IndexRange sel = area.getSelection();
        int first = area.offsetToPosition(sel.getStart(), TwoDimensional.Bias.Forward).getMajor();
        int lastOffset = sel.getEnd() > sel.getStart() ? sel.getEnd() - 1 : sel.getEnd();
        int last = area.offsetToPosition(lastOffset, TwoDimensional.Bias.Forward).getMajor();
        return new IndexRange(first, last);
    }

    private void indentSelection() {
        IndexRange pars = selectedParagraphs();
        String unit = indentUnit();
        for (int p = pars.getStart(); p <= pars.getEnd(); p++) {
            area.insertText(area.getAbsolutePosition(p, 0), unit);
        }
        area.selectRange(area.getAbsolutePosition(pars.getStart(), 0),
                area.getAbsolutePosition(pars.getEnd(), area.getParagraphLength(pars.getEnd())));
    }

    private void unindentSelection() {
        IndexRange pars = selectedParagraphs();
        int width = language.useTabs() ? 1 : Math.max(1, language.indentSize());
        for (int p = pars.getStart(); p <= pars.getEnd(); p++) {
            String line = area.getParagraph(p).getText();
            int remove = 0;
            while (remove < width && remove < line.length() && (line.charAt(remove) == ' ' || line.charAt(remove) == '\t')) {
                remove++;
                if (line.charAt(remove - 1) == '\t') {
                    break;
                }
            }
            if (remove > 0) {
                int start = area.getAbsolutePosition(p, 0);
                area.deleteText(start, start + remove);
            }
        }
    }

    public void showFind() {
        findBar.show(area.getSelectedText());
    }

    public CodeArea area() {
        return area;
    }

    // -------------------------------------------------------------- TextEditor

    @Override
    public Node node() {
        return root;
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public Workspace workspace() {
        return workspace;
    }

    @Override
    public ReadOnlyBooleanProperty modifiedProperty() {
        return modified.getReadOnlyProperty();
    }

    @Override
    public void dispose() {
        disposed = true;
        previewDebounce.stop();
        themeSubscription.cancel();
        split.getItems().clear();
        root.setCenter(null);
        if (preview != null) {
            preview.dispose();
        }
    }

    /** The reader has arrived, so the preview is worth building. */
    @Override
    public void shown() {
        if (disposed || seen) {
            return;
        }
        seen = true;
        if (mode != Mode.RAW) {
            setMode(mode);
        }
    }

    @Override
    public void focus() {
        if (mode == Mode.PREVIEW && preview != null) {
            preview.node().requestFocus();
        } else {
            area.requestFocus();
        }
    }

    @Override
    public String text() {
        return area.getText();
    }

    @Override
    public void setText(String text) {
        area.replaceText(text == null ? "" : text.replace("\r\n", "\n"));
    }

    @Override
    public LanguageSupport language() {
        return language;
    }

    @Override
    public int caretOffset() {
        return area.getCaretPosition();
    }

    @Override
    public int caretLine() {
        return area.getCurrentParagraph();
    }

    @Override
    public int caretColumn() {
        return area.getCaretColumn();
    }

    @Override
    public void moveCaret(int line, int column) {
        int p = Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
        int c = Math.max(0, Math.min(column, area.getParagraphLength(p)));
        area.moveTo(p, c);
        area.requestFollowCaret();
    }

    @Override
    public void select(int startOffset, int endOffset) {
        area.selectRange(clampOffset(startOffset), clampOffset(endOffset));
        area.requestFollowCaret();
    }

    @Override
    public String selectedText() {
        return area.getSelectedText();
    }

    @Override
    public int selectionStart() {
        return area.getSelection().getStart();
    }

    @Override
    public int selectionEnd() {
        return area.getSelection().getEnd();
    }

    @Override
    public void replace(int startOffset, int endOffset, String replacement) {
        area.replaceText(clampOffset(startOffset), clampOffset(endOffset), replacement == null ? "" : replacement);
    }

    @Override
    public void insert(int offset, String text) {
        area.insertText(clampOffset(offset), text);
    }

    @Override
    public int offsetOf(int line, int column) {
        int p = Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
        int c = Math.max(0, Math.min(column, area.getParagraphLength(p)));
        return area.getAbsolutePosition(p, c);
    }

    @Override
    public int lineOf(int offset) {
        return area.offsetToPosition(clampOffset(offset), TwoDimensional.Bias.Forward).getMajor();
    }

    @Override
    public int columnOf(int offset) {
        return area.offsetToPosition(clampOffset(offset), TwoDimensional.Bias.Forward).getMinor();
    }

    @Override
    public int lineCount() {
        return area.getParagraphs().size();
    }

    @Override
    public void setDiagnostics(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        applyStyles();
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public void addTextListener(Consumer<String> listener) {
        textListeners.add(listener);
    }

    @Override
    public void removeTextListener(Consumer<String> listener) {
        textListeners.remove(listener);
    }

    @Override
    public void addCaretListener(Runnable listener) {
        caretListeners.add(listener);
    }

    @Override
    public String wordAtCaret() {
        int caret = area.getCaretPosition();
        String text = area.getText();
        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    @Override
    public void undo() {
        area.undo();
    }

    @Override
    public void redo() {
        area.redo();
    }
}
