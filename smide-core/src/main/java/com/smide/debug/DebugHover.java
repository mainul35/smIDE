package com.smide.debug;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.editor.CodeEditor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * While the program is stopped, pointing at a variable in the editor shows what is in it.
 *
 * <p>The popup is the Debug window's variables tree for that one name: its value and
 * type on the first row, and for an object or an array, an arrow that opens onto its
 * fields or elements, as deep as the reader cares to go.
 *
 * <p>Only names and chains of fields are read - {@code stage}, {@code this.scale},
 * {@code breakpoint.file} - never anything followed by a bracket. Pointing at something
 * must not change the program, and a call would run its code: a getter that counts, a
 * {@code next()}, an {@code add}. That is what the Evaluate field is for, where running
 * it is asked for rather than wandered into.
 */
public final class DebugHover {

    private static final Duration DELAY = Duration.millis(450);
    private static final double ROW = 22;
    private static final int MAX_ROWS = 14;

    private final CodeEditor editor;
    private final Supplier<DebugToolWindow> window;
    private final Popup popup = new Popup();
    private final VBox box;
    private final TreeView<DebugSession.VariableInfo> tree = new TreeView<>();
    private final VariableTree variables;
    private final PauseTransition delay = new PauseTransition(DELAY);
    private final PauseTransition leave = new PauseTransition(Duration.millis(250));
    /** Sessions already told to close this popup when they move on. */
    private final Set<DebugSession> followed = Collections.newSetFromMap(new WeakHashMap<>());
    private int pointerOffset = -1;
    /** True while a value is being asked for, between the delay ending and the popup showing. */
    private boolean deciding;
    /** The word the popup is showing, while it is. */
    private Span shown;

    /**
     * An expression under the pointer.
     *
     * @param expression what is evaluated: the word with the fields leading to it
     * @param start      where the word pointed at starts
     * @param end        where it ends, exclusive
     */
    record Span(String expression, int start, int end) {
    }

    /** Where to read a name: a frame, and whether the line is inside its method. */
    private record Target(DebugSession.StackFrameInfo frame, boolean inMethod) {
    }

    public static DebugHover install(Ide ide, CodeEditor editor, Supplier<DebugToolWindow> window) {
        return new DebugHover(ide, editor, window);
    }

    private DebugHover(Ide ide, CodeEditor editor, Supplier<DebugToolWindow> window) {
        this.editor = editor;
        this.window = window;
        this.variables = new VariableTree(this::session, item -> false);
        variables.install(tree);
        tree.getStyleClass().add("debug-variables");
        tree.setFixedCellSize(ROW);
        tree.setPrefWidth(540);
        tree.setMaxWidth(760);
        /* After the pass, not during it: the count changes while the tree is laying itself
           out, and a size asked for from inside a layout pass never reaches the parents -
           the window grew and the tree inside it stayed one row tall. */
        tree.expandedItemCountProperty().addListener((o, was, now) -> Platform.runLater(this::fit));

        box = new VBox(tree);
        box.getStyleClass().add("debug-hover");
        box.setOnMouseEntered(e -> leave.stop());
        box.setOnMouseExited(e -> leave.playFromStart());
        popup.getContent().add(box);
        // Styled as a window, like every other: the stylesheet on its scene, following the theme.
        ide.theme().style(popup);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(true);
        popup.setOnHidden(e -> shown = null);

        var area = editor.area();
        area.addEventHandler(MouseEvent.MOUSE_MOVED, this::moved);
        area.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            delay.stop();
            pointerOffset = -1;
            if (popup.isShowing()) {
                leave.playFromStart();
            }
        });
        // Anything the reader does with the text means they have stopped looking.
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> hide());
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> hide());
        area.addEventFilter(ScrollEvent.SCROLL, e -> hide());
        delay.setOnFinished(e -> show());
        leave.setOnFinished(e -> {
            if (!pointerOverPopup() && !pointerOverShown()) {
                /* Only the popup, not the timer: the pointer left this value for another,
                   and the show that move scheduled is still wanted. Stopping it too meant
                   going from one variable straight to the next showed nothing at all. */
                popup.hide();
            }
        });
        // Documentation waits while this is up: what a variable holds matters more here.
        /* And while one is about to show: the documentation hover waits 900 ms and this one
           450 ms plus a round trip to the debugger, and when that trip ran long, the language
           server's popup won the race and sat where the value should have been. Once this
           gives up on a word - a type, a function - the documentation is free to show. */
        editor.claimHover(() -> popup.isShowing()
                || delay.getStatus() == javafx.animation.Animation.Status.RUNNING || deciding);
    }

    public void hide() {
        delay.stop();
        leave.stop();
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    /** The tree on show, for tests. */
    TreeView<DebugSession.VariableInfo> tree() {
        return tree;
    }

    Popup popup() {
        return popup;
    }

    private DebugSession session() {
        DebugToolWindow w = window.get();
        return w == null ? null : w.session();
    }

    private void moved(MouseEvent e) {
        if (e.isControlDown()) {
            // Lining up a Ctrl+click to a declaration; a popup under the pointer is in the way.
            delay.stop();
            return;
        }
        int offset = editor.area().hit(e.getX(), e.getY()).getCharacterIndex().orElse(-1);
        pointerOffset = offset;
        if (shown != null && offset >= shown.start() && offset < shown.end()) {
            leave.stop();
            return;
        }
        if (popup.isShowing()) {
            leave.playFromStart();
        }
        DebugSession session = session();
        if (session != null) {
            follow(session);
        }
        if (session != null && session.isSuspended() && offset >= 0) {
            delay.playFromStart();
        } else {
            delay.stop();
        }
    }

    private void show() {
        deciding = true;
        try {
            showNow();
        } finally {
            deciding = false;
        }
    }

    private void showNow() {
        DebugToolWindow w = window.get();
        DebugSession session = w == null ? null : w.session();
        if (session == null || !session.isSuspended() || pointerOffset < 0) {
            return;
        }
        Span span = expressionAt(editor.text(), pointerOffset);
        if (span == null) {
            return;
        }
        Target target = target(w, session, editor.lineOf(span.start()));
        if (target == null) {
            return;
        }
        /* Outside the stopped method a bare name cannot be one of its locals, so it is
           asked for as a field of the object. Asked for plainly, a local of the same name
           in the stopped method would answer - the right name and the wrong variable. */
        String expression = span.expression();
        String asked = target.inMethod() || expression.equals("this") || expression.startsWith("this.")
                ? expression : "this." + expression;
        DebugSession.Evaluation result = session.evaluate(target.frame(), asked);
        if (!result.ok()) {
            // A type, a package, a name not in scope: nothing worth a popup, and not an error.
            return;
        }
        DebugSession.VariableInfo value = result.value();
        DebugSession.VariableInfo named = new DebugSession.VariableInfo(expression, value.type(),
                value.value(), value.expandable(), value.handle());
        Bounds bounds = editor.area().getCharacterBoundsOnScreen(span.start(), span.end()).orElse(null);
        if (bounds == null) {
            return;
        }
        shown = span;
        /* Shown first, filled after. Rows built into a hidden popup are styled with no
           showing scene around them: every colour lookup in them failed, and although they
           came out right a pulse later, each show wrote a screenful of CSS warnings. */
        // The documentation for the same word, if it got there first, would sit under the value.
        editor.dismissOtherHovers();
        popup.show(editor.area(), bounds.getMinX(), bounds.getMaxY() + 4);
        tree.setShowRoot(true);
        tree.setRoot(variables.node(named));
        fit();
    }

    /**
     * The frame a name in this file is read in.
     *
     * <p>The frame selected in the Debug window first - it is the one the reader is
     * looking at - then any other on the stack that is running code in this file. One
     * whose method holds the line wins; failing that, one in the same file, for fields.
     */
    private Target target(DebugToolWindow w, DebugSession session, int line) {
        List<DebugSession.StackFrameInfo> candidates = new ArrayList<>();
        DebugSession.StackFrameInfo selected = w.selectedFrame();
        if (selected != null) {
            candidates.add(selected);
        }
        candidates.addAll(w.frames());
        DebugSession.StackFrameInfo sameFile = null;
        for (DebugSession.StackFrameInfo frame : candidates) {
            if (frame.file() == null || !same(frame.file(), editor.path())) {
                continue;
            }
            if (session.frameContains(frame, line)) {
                return new Target(frame, true);
            }
            if (sameFile == null) {
                sameFile = frame;
            }
        }
        return sameFile == null ? null : new Target(sameFile, false);
    }

    /**
     * Whether two paths name the same file.
     *
     * <p>Spelled alike first, then asked of the file system: a debugger reports the path the
     * compiler recorded, which on Windows can differ from the editor's in case, in a short
     * 8.3 name or through a junction, and a frame that seemed to be in another file gave the
     * hover no frame to read the name in, so it showed nothing.
     */
    static boolean same(Path a, Path b) {
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return a.getFileName() != null && b.getFileName() != null
                    && a.getFileName().toString().equalsIgnoreCase(b.getFileName().toString())
                    && java.nio.file.Files.isSameFile(a, b);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private void follow(DebugSession session) {
        // A step or a resume changes every value; an old one left on screen would be a lie.
        if (followed.add(session)) {
            session.addListener(s -> {
                hide();
                /* A step ends with the pointer where it was - often on the very name whose
                   value just changed - and nothing moved to ask again, so the language
                   server's documentation, shown while the program ran, stayed instead. */
                if (s.isSuspended() && pointerOffset >= 0) {
                    delay.playFromStart();
                }
            });
        }
    }

    /** As tall as the rows open, up to a limit, so a closed value is one line and not a box. */
    private void fit() {
        int rows = Math.max(1, Math.min(MAX_ROWS, tree.getExpandedItemCount()));
        tree.setPrefHeight(rows * ROW + 4);
        tree.setMinHeight(rows * ROW + 4);
        if (popup.isShowing()) {
            // The box first, then the window around it: resizing only the window left the
            // box at its old height inside a larger, empty popup.
            box.applyCss();
            box.autosize();
            popup.sizeToScene();
        }
    }

    private boolean pointerOverPopup() {
        return popup.isShowing() && box.isHover();
    }

    private boolean pointerOverShown() {
        return shown != null && pointerOffset >= shown.start() && pointerOffset < shown.end();
    }

    // ------------------------------------------------------------------ reading

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "var", "record", "yield", "sealed", "permits");

    /**
     * The expression at an offset, or null when there is nothing there worth reading.
     *
     * <p>The word under the pointer, with the fields that lead to it: pointing at
     * {@code file} in {@code breakpoint.file} asks for {@code breakpoint.file}, because a
     * bare {@code file} is some other variable, or none. Refused: a word followed by a
     * bracket (a call, which would run), a chain reached through a call or an index
     * ({@code key().file}), keywords and literals, and anything inside a string or a
     * comment - where a word is just a word.
     */
    static Span expressionAt(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()
                || !Character.isJavaIdentifierPart(text.charAt(offset))) {
            return null;
        }
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (!Character.isJavaIdentifierStart(text.charAt(start))) {
            return null;
        }
        String word = text.substring(start, end);
        if (KEYWORDS.contains(word) || followedByBracket(text, end) || insideStringOrComment(text, start)) {
            return null;
        }
        if (start > 0 && text.charAt(start - 1) == '@') {
            return null;
        }
        int chainStart = start;
        while (chainStart > 0 && text.charAt(chainStart - 1) == '.') {
            int previousEnd = chainStart - 1;
            int previousStart = previousEnd;
            while (previousStart > 0 && Character.isJavaIdentifierPart(text.charAt(previousStart - 1))) {
                previousStart--;
            }
            if (previousStart == previousEnd || !Character.isJavaIdentifierStart(text.charAt(previousStart))) {
                // ")." or "]." or "2.": reached through something this will not repeat.
                return null;
            }
            chainStart = previousStart;
        }
        String expression = text.substring(chainStart, end);
        String first = expression.contains(".") ? expression.substring(0, expression.indexOf('.')) : expression;
        if (!first.equals(word) && KEYWORDS.contains(first)) {
            // super.x and the like: not something the evaluator reads.
            return null;
        }
        return new Span(expression, start, end);
    }

    private static boolean followedByBracket(String text, int end) {
        int i = end;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '(';
    }

    /**
     * Whether a position is inside a string, a character literal or a comment.
     *
     * <p>A scan of the line for strings and line comments, and a look back for an
     * unclosed block comment. Not a lexer - a {@code /*} inside a string earlier in the
     * file can fool it - but the cost of being fooled is a popup not shown.
     */
    private static boolean insideStringOrComment(String text, int position) {
        int open = text.lastIndexOf("/*", position);
        if (open >= 0 && text.lastIndexOf("*/", position) < open) {
            return true;
        }
        int lineStart = text.lastIndexOf('\n', position - 1) + 1;
        char quote = 0;
        for (int i = lineStart; i < position; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < position && text.charAt(i + 1) == '/') {
                return true;
            }
        }
        return quote != 0;
    }
}
