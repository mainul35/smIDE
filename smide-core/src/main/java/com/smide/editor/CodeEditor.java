package com.smide.editor;

import com.smide.api.editor.TextEditor;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.Token;
import com.smide.api.problems.Diagnostic;
import com.smide.api.workspace.Workspace;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.TwoDimensional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The code editor: a RichTextFX {@link CodeArea} with line numbers, background syntax
 * highlighting from the language's tokenizer, bracket pairing, auto-indent, a find bar,
 * and diagnostics painted as underlines.
 *
 * <p>Text is normalised to {@code \n} inside; the file's own line separator is
 * remembered and written back on save.
 */
public final class CodeEditor implements TextEditor {

    private static final ExecutorService HIGHLIGHTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smide-highlighter");
        t.setDaemon(true);
        return t;
    });
    private static final int HIGHLIGHT_LIMIT = 2_000_000;

    /**
     * The editor font unless Settings says otherwise: the bundled JetBrains Mono, or the
     * best monospace this machine has if it could not be loaded.
     */
    public static final String DEFAULT_FONT = com.smide.ui.Fonts.monospace();

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
    private final BorderPane root = new BorderPane();
    private final FindBar findBar;
    private final Workspace workspace;
    private final LanguageSupport language;
    /** Kept, so a change to the font applies to this editor and not only to the next one. */
    private com.smide.api.settings.Settings settings;
    private final ReadOnlyBooleanWrapper modified = new ReadOnlyBooleanWrapper(false);
    private final List<Consumer<String>> textListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> caretListeners = new CopyOnWriteArrayList<>();

    private Path path;
    private GutterFactory gutter;
    private String lineSeparator = System.lineSeparator();
    private FileTime loadedTime;
    private List<Token> tokens = List.of();
    private List<Diagnostic> diagnostics = List.of();
    private List<EditorStyles.Overlay> searchHits = List.of();
    private int highlightGeneration;
    private boolean disposed;

    public CodeEditor(Workspace workspace, Path path, LanguageSupport language) {
        this(workspace, path, language, null);
    }

    public CodeEditor(Workspace workspace, Path path, LanguageSupport language,
                      com.smide.api.settings.Settings settings) {
        this(workspace, path, language, settings, null);
    }

    public CodeEditor(Workspace workspace, Path path, LanguageSupport language,
                      com.smide.api.settings.Settings settings,
                      com.smide.api.debug.Breakpoints breakpoints) {
        this.workspace = workspace;
        this.path = path.toAbsolutePath().normalize();
        this.language = language;
        area.getStyleClass().add("code-area");
        if (breakpoints != null) {
            gutter = new GutterFactory(area, breakpoints, this.path);
            area.setParagraphGraphicFactory(gutter);
        } else {
            area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        }
        area.setLineHighlighterOn(false);
        this.settings = settings;
        applyDisplaySettings();
        root.getStyleClass().add("code-editor");
        root.setCenter(scroll);
        findBar = new FindBar(this);
        root.setTop(findBar);
        findBar.setVisible(false);
        findBar.setManaged(false);

        load();

        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(120))
                .subscribe(ignore -> {
                    scheduleHighlight();
                    String text = area.getText();
                    textListeners.forEach(l -> l.accept(text));
                });
        area.caretPositionProperty().addListener((obs, was, now) -> {
            caretListeners.forEach(Runnable::run);
        });
        area.getUndoManager().atMarkedPositionProperty().addListener((obs, was, now) -> modified.set(!now));
        area.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        area.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                this::onContextMenuRequested);
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
    }

    // ---------------------------------------------------------------- loading

    private void load() {
        String content = "";
        try {
            byte[] bytes = Files.readAllBytes(path);
            content = new String(bytes, StandardCharsets.UTF_8);
            loadedTime = Files.getLastModifiedTime(path);
        } catch (IOException e) {
            System.err.println("smIDE: cannot read " + path + ": " + e);
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
        scheduleHighlight();
    }

    @Override
    public void reload() {
        int caret = area.getCaretPosition();
        load();
        area.moveTo(Math.min(caret, area.getLength()));
    }

    /** True if the file on disk is newer than what was loaded and the editor has no changes. */
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
                System.err.println("smIDE: highlighter for " + language.id() + " failed: " + e);
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
        List<EditorStyles.Overlay> overlays = new ArrayList<>(searchHits);
        for (Diagnostic d : diagnostics) {
            int start = clampOffset(offsetOf(d.startLine(), d.startColumn()));
            int end = clampOffset(offsetOf(d.endLine(), d.endColumn()));
            if (end <= start) {
                end = Math.min(length, start + 1);
            }
            if (end > start) {
                overlays.add(new EditorStyles.Overlay(start, end, "diag-" + d.severity().name().toLowerCase(Locale.ROOT)));
            }
        }
        List<Token> safe = tokens;
        try {
            area.setStyleSpans(0, EditorStyles.spans(length, safe, overlays));
        } catch (RuntimeException e) {
            // Text changed under us; the next pass repaints.
        }
    }

    private int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, area.getLength()));
    }

    void setSearchHits(List<EditorStyles.Overlay> hits) {
        this.searchHits = hits;
        applyStyles();
    }

    // ------------------------------------------------------------- key handling

    /**
     * A press in the breakpoint column toggles a breakpoint rather than moving the caret.
     *
     * <p>Handled here rather than on the gutter node itself: the area filters mouse
     * presses to place the caret before any child of a paragraph can see them, so a
     * handler on the column never ran.
     */
    private void onMousePressed(javafx.scene.input.MouseEvent e) {
        if (gutter == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
            return;
        }
        Integer run = runMarkerLine(e.getPickResult());
        if (run != null) {
            // The run icon runs, rather than setting a breakpoint on the line it sits on.
            e.consume();
            gutter.showRunMenu(area, run, e.getScreenX(), e.getScreenY());
            return;
        }
        javafx.scene.layout.Pane swatch = swatchCell(e.getPickResult());
        Integer line = gutterLine(e.getPickResult());
        if (swatch != null && line != null) {
            // The colour swatch offers another colour, rather than a breakpoint.
            e.consume();
            gutter.showColorPicker(swatch, line);
            return;
        }
        if (line != null) {
            e.consume();
            gutter.toggleAt(line);
        }
    }

    /** The colour swatch's cell under the pointer, or null where the pointer is not on one. */
    private static javafx.scene.layout.Pane swatchCell(javafx.scene.input.PickResult pick) {
        javafx.scene.Node picked = pick == null ? null : pick.getIntersectedNode();
        while (picked != null && !picked.getStyleClass().contains("gutter")) {
            if (picked.getStyleClass().contains("color-swatch-cell")
                    && picked instanceof javafx.scene.layout.Pane cell) {
                return cell;
            }
            picked = picked.getParent();
        }
        return null;
    }

    /**
     * A right click on the gutter opens the breakpoint menu instead of the editing one.
     *
     * <p>A filter, for the same reason as {@link #onMousePressed}: the area's own handler
     * would otherwise answer first, with Cut, Copy and Paste for a click that was never
     * on the text.
     */
    private void onContextMenuRequested(javafx.scene.input.ContextMenuEvent e) {
        if (gutter == null) {
            return;
        }
        Integer run = runMarkerLine(e.getPickResult());
        if (run != null) {
            e.consume();
            if (area.getContextMenu() != null) {
                area.getContextMenu().hide();
            }
            gutter.showRunMenu(area, run, e.getScreenX(), e.getScreenY());
            return;
        }
        Integer line = gutterLine(e.getPickResult());
        if (line == null) {
            return;
        }
        e.consume();
        if (area.getContextMenu() != null) {
            area.getContextMenu().hide();
        }
        gutter.showMenu(area, line, e.getScreenX(), e.getScreenY());
    }

    /** The line of the run icon under the pointer, or null when the pointer is not on one. */
    private static Integer runMarkerLine(javafx.scene.input.PickResult pick) {
        javafx.scene.Node picked = pick == null ? null : pick.getIntersectedNode();
        boolean onIcon = false;
        while (picked != null && !picked.getStyleClass().contains("gutter")) {
            onIcon |= picked.getStyleClass().contains("run-marker");
            picked = picked.getParent();
        }
        return onIcon && picked != null && picked.getUserData() instanceof Integer line ? line : null;
    }

    /**
     * The line of the gutter row under the pointer, or null when it is not over the gutter.
     *
     * <p>Which node was actually under the pointer, rather than where it was: the
     * editor's left edge moves with the tool windows, so a coordinate test against a
     * fixed width was wrong as soon as the Project panel changed size.
     */
    private static Integer gutterLine(javafx.scene.input.PickResult pick) {
        javafx.scene.Node picked = pick == null ? null : pick.getIntersectedNode();
        while (picked != null && !picked.getStyleClass().contains("gutter")) {
            picked = picked.getParent();
        }
        return picked != null && picked.getUserData() instanceof Integer line ? line : null;
    }

    private void onKeyPressed(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER && !e.isControlDown() && !e.isShiftDown()) {
            e.consume();
            insertNewlineWithIndent();
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
        } else if (e.getCode() == KeyCode.ESCAPE && findBar.isVisible()) {
            e.consume();
            findBar.hideBar();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (ch == null || ch.length() != 1 || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        char c = ch.charAt(0);
        String pairs = language.bracketPairs();
        int idx = pairs.indexOf(c);
        int caret = area.getCaretPosition();
        char next = caret < area.getLength() ? area.getText(caret, caret + 1).charAt(0) : '\n';

        // Typing a closer that is already there: step over it.
        if (idx >= 0 && idx % 2 == 1 && next == c) {
            e.consume();
            area.moveTo(caret + 1);
            return;
        }
        if (c == '"' || c == '\'') {
            if (next == c) {
                e.consume();
                area.moveTo(caret + 1);
                return;
            }
            char prev = caret > 0 ? area.getText(caret - 1, caret).charAt(0) : ' ';
            if (!Character.isLetterOrDigit(prev) && (Character.isWhitespace(next) || ")]},;".indexOf(next) >= 0)
                    && !Character.isLetterOrDigit(next)) {
                e.consume();
                area.replaceSelection(String.valueOf(c) + c);
                area.moveTo(caret + 1);
            }
            return;
        }
        if (idx >= 0 && idx % 2 == 0) {
            if (Character.isWhitespace(next) || ")]}".indexOf(next) >= 0 || next == ';' || next == ',') {
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
    }

    private boolean isPair(char open, char close) {
        String pairs = language.bracketPairs();
        int idx = pairs.indexOf(open);
        return (idx >= 0 && idx % 2 == 0 && pairs.charAt(idx + 1) == close)
                || (open == '"' && close == '"') || (open == '\'' && close == '\'');
    }

    private void insertNewlineWithIndent() {
        int caret = area.getCaretPosition();
        int par = area.getCurrentParagraph();
        String line = area.getParagraph(par).getText();
        int col = area.getCaretColumn();
        String indent = leadingWhitespace(line);
        String before = line.substring(0, Math.min(col, line.length())).stripTrailing();
        String after = line.substring(Math.min(col, line.length()));
        boolean opens = !before.isEmpty() && language.indentOpeners().indexOf(before.charAt(before.length() - 1)) >= 0;
        boolean closes = !after.isEmpty() && ")]}".indexOf(after.stripLeading().isEmpty() ? ' ' : after.stripLeading().charAt(0)) >= 0;
        StringBuilder sb = new StringBuilder("\n").append(indent);
        int caretOffset = sb.length();
        if (opens) {
            sb.append(indentUnit());
            caretOffset = sb.length();
            if (closes) {
                sb.append("\n").append(indent);
            }
        }
        area.replaceSelection(sb.toString());
        area.moveTo(caret + caretOffset);
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
            int start = area.getAbsolutePosition(p, 0);
            area.insertText(start, unit);
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

    // ------------------------------------------------------- editing commands

    public void toggleLineComment() {
        String prefix = language.lineComment();
        if (prefix == null) {
            LanguageSupport.BlockComment block = language.blockComment();
            if (block != null) {
                toggleBlockComment();
            }
            return;
        }
        IndexRange pars = selectedParagraphs();
        boolean allCommented = true;
        for (int p = pars.getStart(); p <= pars.getEnd(); p++) {
            String line = area.getParagraph(p).getText();
            if (!line.isBlank() && !line.stripLeading().startsWith(prefix)) {
                allCommented = false;
                break;
            }
        }
        for (int p = pars.getStart(); p <= pars.getEnd(); p++) {
            String line = area.getParagraph(p).getText();
            int start = area.getAbsolutePosition(p, 0);
            if (allCommented) {
                int idx = line.indexOf(prefix);
                if (idx >= 0) {
                    int end = idx + prefix.length();
                    if (end < line.length() && line.charAt(end) == ' ') {
                        end++;
                    }
                    area.deleteText(start + idx, start + end);
                }
            } else if (!line.isBlank()) {
                int indent = leadingWhitespace(line).length();
                area.insertText(start + indent, prefix + " ");
            }
        }
        area.selectRange(area.getAbsolutePosition(pars.getStart(), 0),
                area.getAbsolutePosition(pars.getEnd(), area.getParagraphLength(pars.getEnd())));
    }

    public void toggleBlockComment() {
        LanguageSupport.BlockComment block = language.blockComment();
        if (block == null) {
            return;
        }
        IndexRange sel = area.getSelection();
        String selected = area.getSelectedText();
        if (selected.startsWith(block.start()) && selected.endsWith(block.end())) {
            String inner = selected.substring(block.start().length(), selected.length() - block.end().length());
            area.replaceText(sel.getStart(), sel.getEnd(), inner);
            area.selectRange(sel.getStart(), sel.getStart() + inner.length());
        } else {
            String wrapped = block.start() + selected + block.end();
            area.replaceText(sel.getStart(), sel.getEnd(), wrapped);
            area.selectRange(sel.getStart(), sel.getStart() + wrapped.length());
        }
    }

    public void duplicateLineOrSelection() {
        IndexRange sel = area.getSelection();
        if (sel.getLength() > 0) {
            String text = area.getSelectedText();
            area.insertText(sel.getEnd(), text);
            area.selectRange(sel.getEnd(), sel.getEnd() + text.length());
            return;
        }
        int p = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        String line = area.getParagraph(p).getText();
        int end = area.getAbsolutePosition(p, line.length());
        area.insertText(end, "\n" + line);
        area.moveTo(p + 1, col);
    }

    public void deleteLine() {
        IndexRange pars = selectedParagraphs();
        int start = area.getAbsolutePosition(pars.getStart(), 0);
        int end = area.getAbsolutePosition(pars.getEnd(), area.getParagraphLength(pars.getEnd()));
        if (end < area.getLength()) {
            end++;
        } else if (start > 0) {
            start--;
        }
        int col = area.getCaretColumn();
        area.deleteText(start, end);
        int p = Math.min(pars.getStart(), area.getParagraphs().size() - 1);
        area.moveTo(p, Math.min(col, area.getParagraphLength(p)));
    }

    public void moveLines(boolean up) {
        IndexRange pars = selectedParagraphs();
        int first = pars.getStart();
        int last = pars.getEnd();
        int count = area.getParagraphs().size();
        if (up ? first == 0 : last >= count - 1) {
            return;
        }
        int col = area.getCaretColumn();
        int caretPar = area.getCurrentParagraph();
        int blockStart = area.getAbsolutePosition(first, 0);
        int blockEnd = area.getAbsolutePosition(last, area.getParagraphLength(last));
        String block = area.getText(blockStart, blockEnd);
        if (up) {
            int prevStart = area.getAbsolutePosition(first - 1, 0);
            String prev = area.getParagraph(first - 1).getText();
            area.replaceText(prevStart, blockEnd, block + "\n" + prev);
        } else {
            int nextEnd = area.getAbsolutePosition(last + 1, area.getParagraphLength(last + 1));
            String next = area.getParagraph(last + 1).getText();
            area.replaceText(blockStart, nextEnd, next + "\n" + block);
        }
        int delta = up ? -1 : 1;
        if (pars.getLength() > 0 || first != last) {
            area.selectRange(area.getAbsolutePosition(first + delta, 0),
                    area.getAbsolutePosition(last + delta, area.getParagraphLength(last + delta)));
        } else {
            area.moveTo(caretPar + delta, Math.min(col, area.getParagraphLength(caretPar + delta)));
        }
    }

    public void gotoLine(int line) {
        int p = Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
        area.moveTo(p, 0);
        area.requestFollowCaret();
        area.requestFocus();
    }

    public void showFind(boolean withReplace) {
        findBar.show(withReplace, area.getSelectedText());
    }

    public FindBar findBar() {
        return findBar;
    }

    public CodeArea area() {
        return area;
    }

    /** Redraws the breakpoint column, after a breakpoint was added or removed. */
    public void refreshGutter() {
        if (gutter != null) {
            gutter.refresh();
        }
    }

    /** What the run icons showed last: the names on each line, so an unchanged scan repaints nothing. */
    private java.util.Map<Integer, List<String>> shownRunMarkers = java.util.Map.of();

    /**
     * Shows run icons beside the lines a run can start from.
     *
     * @param menu what a click on an icon opens, made from the markers on its line
     */
    public void setRunMarkers(java.util.Map<Integer, List<com.smide.api.execution.RunMarker>> markers,
                              java.util.function.Function<List<com.smide.api.execution.RunMarker>, javafx.scene.control.ContextMenu> menu) {
        if (gutter == null || disposed) {
            return;
        }
        java.util.Map<Integer, List<String>> names = new java.util.TreeMap<>();
        markers.forEach((line, here) -> names.put(line,
                here.stream().map(com.smide.api.execution.RunMarker::name).toList()));
        gutter.setRunMarkers(markers, menu);
        if (!names.equals(shownRunMarkers)) {
            shownRunMarkers = names;
            gutter.refresh();
        }
    }

    public boolean isDisposed() {
        return disposed;
    }

    /** The colours shown last, so a scan that found the same ones repaints nothing. */
    private java.util.Map<Integer, List<ColorSwatches.Literal>> shownColors = java.util.Map.of();

    /** Shows a swatch beside each line that writes a colour, by zero-based line. */
    public void setColorSwatches(java.util.Map<Integer, List<ColorSwatches.Literal>> colors) {
        if (gutter == null || disposed || colors.equals(shownColors)) {
            return;
        }
        shownColors = colors;
        gutter.setColors(colors);
        gutter.refresh();
    }

    /**
     * Marks this as source read out of a library rather than code from the project.
     *
     * <p>It gets a yellow paper colour and is read-only, so a tab full of Spring's source
     * is not mistaken at a glance for a file you can edit and keep.
     */
    public void markExternalSource() {
        if (!area.getStyleClass().contains("external-source")) {
            area.getStyleClass().add("external-source");
            root.getStyleClass().add("external-source");
        }
        /* Read-only, because it is: the copy is written fresh out of the jar every time
           the declaration is opened, so an edit here would be thrown away without a word. */
        area.setEditable(false);
    }

    /** Whether something else is showing a popup for the pointer, which documentation should not cover. */
    private java.util.function.BooleanSupplier hoverClaim = () -> false;

    /**
     * Lets another popup for the pointer - the debugger's values - keep documentation away
     * while it is showing, so the two do not stack on the same word.
     */
    public void claimHover(java.util.function.BooleanSupplier claim) {
        this.hoverClaim = claim == null ? () -> false : claim;
    }

    public boolean isHoverClaimed() {
        return hoverClaim.getAsBoolean();
    }

    /** Popups that close when the claiming one shows: documentation under a value, say. */
    private final List<Runnable> hoverDismissers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers a popup to close when a claiming hover shows. */
    public void addHoverDismisser(Runnable dismiss) {
        hoverDismissers.add(dismiss);
    }

    public void removeHoverDismisser(Runnable dismiss) {
        hoverDismissers.remove(dismiss);
    }

    /** Closes every registered popup; called by the hover that claims the pointer as it shows. */
    public void dismissOtherHovers() {
        hoverDismissers.forEach(Runnable::run);
    }

    /** Right-click menu for the text itself; the actions decide what is in it. */
    public void setContextMenu(javafx.scene.control.ContextMenu menu) {
        area.setContextMenu(menu);
    }

    /**
     * Applies the font and wrapping from settings.
     *
     * <p>Called again whenever those settings change, so a new size lands in the editors
     * that are already open. It used to run only in the constructor, which meant a font
     * change showed up in the next file opened and nowhere else.
     */
    public void applyDisplaySettings() {
        if (settings == null) {
            return;
        }
        String family = settings.get("editor.fontFamily", DEFAULT_FONT).replace("\"", "");
        int size = settings.getInt("editor.fontSize", 13);
        area.setStyle("-fx-font-family: \"" + family + "\", " + com.smide.ui.Fonts.cssStack() + ";"
                + " -fx-font-size: " + size + "px;");
        area.setWrapText(settings.getBoolean("editor.wrap", false));
        if (gutter != null) {
            // The gutter is measured in the new font too, so the numbers stay lined up.
            gutter.refresh();
        }
    }

    @Override
    public void setLineAnnotations(com.smide.api.editor.LineAnnotations annotations) {
        if (gutter == null) {
            return;
        }
        gutter.setAnnotations(annotations);
        gutter.refresh();
    }

    @Override
    public com.smide.api.editor.LineAnnotations lineAnnotations() {
        return gutter == null ? null : gutter.annotations();
    }

    /**
     * Marks the line the debugger has stopped on, scrolling it into view; -1 clears it.
     */
    public void setExecutionLine(int line) {
        if (gutter == null) {
            return;
        }
        gutter.setExecutionLine(line);
        gutter.refresh();
        if (line >= 0 && line < area.getParagraphs().size()) {
            area.moveTo(line, 0);
            area.requestFollowCaret();
        }
    }

    public int executionLine() {
        return gutter == null ? -1 : gutter.executionLine();
    }

    public String lineSeparatorName() {
        return "\r\n".equals(lineSeparator) ? "CRLF" : "LF";
    }

    // ----------------------------------------------------------- TextEditor

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
    }

    @Override
    public void focus() {
        area.requestFocus();
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
