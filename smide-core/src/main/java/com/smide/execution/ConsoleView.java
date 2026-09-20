package com.smide.execution;

import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only styled log with a stdin line at the bottom, a toolbar down its left edge,
 * and clickable file references in the output.
 */
public final class ConsoleView extends BorderPane {

    /** Something in the output that points at a file: {@code (Foo.java:12)} or {@code /path/x.py:3}. */
    public record FileRef(String fileName, int line, int column) {
    }

    private static final Pattern STACK_FRAME = Pattern.compile("\\(([\\w$]+\\.(?:java|kt|scala|groovy)):(\\d+)\\)");
    private static final Pattern PATH_REF = Pattern.compile(
            "((?:[A-Za-z]:)?[\\w./\\\\~-]+\\.[A-Za-z0-9]{1,8}):(\\d+)(?::(\\d+))?");
    private static final int MAX_CHARS = 2_000_000;

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final AnsiParser ansi = new AnsiParser();
    private final TextField input = new TextField();
    private final ToggleButton scrollLock = new ToggleButton();
    private final VBox toolbar = new VBox();
    private final StringBuilder buffer = new StringBuilder();
    private final List<List<String>> bufferStyles = new ArrayList<>();
    private final Object lock = new Object();
    private boolean flushScheduled;
    private Consumer<FileRef> onFileRef;
    private Consumer<String> onInput;

    public ConsoleView() {
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("console-area");
        area.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getClickCount() == 1 && onFileRef != null) {
                handleClick();
            }
        });
        VirtualizedScrollPane<StyleClassedTextArea> output = new VirtualizedScrollPane<>(area);
        output.setMinHeight(0);
        setCenter(output);

        input.getStyleClass().add("console-input");
        input.setPromptText("Send to the process's standard input, Enter to send");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && onInput != null) {
                onInput.accept(input.getText() + System.lineSeparator());
                appendSystem("> " + input.getText());
                input.clear();
                e.consume();
            }
        });
        setBottom(input);

        scrollLock.setGraphic(Icons.of("fth-arrow-down-circle", 14));
        scrollLock.setTooltip(com.smide.api.ui.Tooltips.of("Scroll to the end"));
        scrollLock.setSelected(true);
        scrollLock.getStyleClass().add("icon-button");
        scrollLock.setFocusTraversable(false);
        toolbar.getStyleClass().add("console-toolbar");
        toolbar.getChildren().add(scrollLock);
        toolbar.setAlignment(Pos.TOP_CENTER);
        /* A column of buttons is at least as tall as its buttons, and a border pane is at
           least as tall as its tallest side. A Run window shorter than the four buttons
           therefore kept the console at their height and cut off its bottom - the input
           field, so a program asking for input could not be answered. The column may now
           shrink, hiding the buttons that do not fit rather than the field. */
        toolbar.setMinHeight(0);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(toolbar.widthProperty());
        clip.heightProperty().bind(toolbar.heightProperty());
        toolbar.setClip(clip);
        input.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        setMinHeight(0);
        setLeft(toolbar);
    }

    public VBox toolbar() {
        return toolbar;
    }

    public void setOnFileRef(Consumer<FileRef> handler) {
        this.onFileRef = handler;
    }

    public void setOnInput(Consumer<String> handler) {
        this.onInput = handler;
        input.setDisable(handler == null);
    }

    public void setInputEnabled(boolean enabled) {
        input.setDisable(!enabled);
    }

    /** Any thread. */
    public void append(String chunk) {
        List<AnsiParser.Run> runs;
        synchronized (ansi) {
            runs = ansi.feed(chunk);
        }
        synchronized (lock) {
            for (AnsiParser.Run run : runs) {
                buffer.append(run.text());
                bufferStyles.add(List.of(run.text().length() + "", String.join(" ", run.styles())));
            }
        }
        scheduleFlush();
    }

    /** A line from the IDE itself, in muted italics. Any thread. */
    public void appendSystem(String line) {
        Platform.runLater(() -> {
            flushNow();
            appendStyled(line + "\n", List.of("console-system"));
        });
    }

    public void appendError(String line) {
        Platform.runLater(() -> {
            flushNow();
            appendStyled(line + "\n", List.of("console-error"));
        });
    }

    private void scheduleFlush() {
        synchronized (lock) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        Platform.runLater(this::flushNow);
    }

    private void flushNow() {
        String text;
        List<List<String>> styles;
        synchronized (lock) {
            flushScheduled = false;
            if (buffer.length() == 0) {
                return;
            }
            text = buffer.toString();
            styles = new ArrayList<>(bufferStyles);
            buffer.setLength(0);
            bufferStyles.clear();
        }
        int pos = 0;
        for (List<String> s : styles) {
            int len = Integer.parseInt(s.get(0));
            String piece = text.substring(pos, pos + len);
            List<String> classes = s.get(1).isEmpty() ? List.of() : List.of(s.get(1).split(" "));
            appendStyled(piece, classes);
            pos += len;
        }
    }

    private void appendStyled(String text, List<String> classes) {
        int start = area.getLength();
        area.appendText(text);
        if (!classes.isEmpty()) {
            area.setStyle(start, start + text.length(), classes);
        }
        markLinks(start, text);
        if (area.getLength() > MAX_CHARS) {
            area.deleteText(0, area.getLength() - MAX_CHARS);
        }
        if (scrollLock.isSelected()) {
            area.moveTo(area.getLength());
            area.requestFollowCaret();
        }
    }

    private void markLinks(int base, String text) {
        for (Pattern p : List.of(STACK_FRAME, PATH_REF)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                int start = base + m.start(1);
                int end = base + m.end(2);
                List<String> existing = new ArrayList<>(area.getStyleOfChar(start));
                if (!existing.contains("console-link")) {
                    existing.add("console-link");
                    area.setStyle(start, end, existing);
                }
            }
        }
    }

    private void handleClick() {
        int offset = area.getCaretPosition();
        if (offset < 0 || offset >= area.getLength()) {
            return;
        }
        if (!area.getStyleOfChar(offset).contains("console-link")) {
            return;
        }
        int par = area.offsetToPosition(offset, TwoDimensional.Bias.Forward).getMajor();
        int col = area.offsetToPosition(offset, TwoDimensional.Bias.Forward).getMinor();
        String line = area.getParagraph(par).getText();
        for (Pattern p : List.of(STACK_FRAME, PATH_REF)) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                if (col >= m.start() && col <= m.end()) {
                    int column = p == PATH_REF && m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
                    onFileRef.accept(new FileRef(m.group(1), Integer.parseInt(m.group(2)), column));
                    return;
                }
            }
        }
    }

    public void clear() {
        area.clear();
    }

    public String text() {
        return area.getText();
    }
}
