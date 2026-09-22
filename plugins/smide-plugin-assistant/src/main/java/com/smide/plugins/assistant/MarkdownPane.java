package com.smide.plugins.assistant;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.Token;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown drawn with JavaFX controls, and no browser anywhere near it.
 *
 * <p>This replaces a WebView. The WebView worked and then killed the process: SIGABRT
 * inside libjfxwebkit whenever the panel was opened on Linux, three times, on two
 * different builds, with no Java stack to show for it because the abort came from native
 * code. Handing WebKit less to do made it less frequent and not less fatal, and an IDE
 * that dies when you click a tool window is not one anybody will click twice.
 *
 * <p>What is lost with the browser: selectable text, tables, images, and the preview's
 * exact typography. What is gained: it cannot crash the process, it is crisp at any zoom
 * because these are real nodes rather than a scaled bitmap, and it starts instantly.
 * Copy still takes the whole transcript as Markdown, which is what it was for.
 */
final class MarkdownPane extends ScrollPane {

    private static final Parser PARSER = Parser.builder().build();

    private final VBox content = new VBox(2);
    /**
     * Over the text: the bands that show what is selected.
     *
     * <p>Over, not behind, and translucent. Behind the text it was invisible wherever anything
     * drew a background of its own - which is every fenced block, the one place people most want
     * to take something from. Dragging over a command selected it perfectly and showed nothing at
     * all, so nobody believed it had worked, which is the same as it not working.
     */
    private final javafx.scene.layout.Pane highlight = new javafx.scene.layout.Pane();
    /** The whole of it, so the highlight and the text share a coordinate space. */
    private final javafx.scene.layout.StackPane layers = new javafx.scene.layout.StackPane(content, highlight);
    private final TextSelection selection = new TextSelection(highlight, layers);
    /** For the colouring of fenced blocks; the IDE's own language plugins do it. */
    private final com.smide.api.Ide ide;
    private boolean follow = true;
    /** What was last shown, for a copy that wants the Markdown rather than the drawing. */
    private String shown = "";
    /** Which folded results the reader has opened, by what is in them; see {@link #folded}. */
    private final java.util.Set<Integer> opened = new java.util.HashSet<>();

    MarkdownPane(com.smide.api.Ide ide) {
        this.ide = ide;
        // The click that starts a selection ends with a scroll pane holding the focus - this one,
        // or a code block's own - so the shortcuts are watched for from up here, where every key
        // pressed anywhere inside passes on its way down.
        selection.keysFrom(this);
        selection.wholeText(() -> shown);
        content.getStyleClass().add("md-pane");
        content.setPadding(new Insets(10, 12, 18, 12));
        content.setFillWidth(true);
        layers.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        layers.setFocusTraversable(true);
        setContent(layers);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("md-scroll");
    }

    /** Lets go of what was selected, for whoever needs the words left plain. */
    void clearSelection() {
        selection.clear();
    }

    /** What the reader has selected with the mouse, for whoever wants to copy it. */
    String selectedText() {
        return selection.selected();
    }

    /** Whether new text scrolls the view down with it. */
    void setFollow(boolean follow) {
        this.follow = follow;
    }

    /** Replaces what is shown. */
    void show(String markdown) {
        // Whether the reader was already at the bottom decides whether they get dragged
        // there again: someone who has scrolled up to re-read is not to be yanked back.
        boolean pinned = getVvalue() >= 0.98 || content.getChildren().isEmpty();
        shown = markdown == null ? "" : markdown;
        content.getChildren().setAll(blocks(PARSER.parse(shown)));
        // The text moved; what was selected of it is gone, and the pieces are found again.
        javafx.application.Platform.runLater(() -> selection.rebuilt(TextSelection.textsUnder(content)));
        if (follow && pinned) {
            // After layout, or the value is set against the old height and lands short.
            javafx.application.Platform.runLater(() -> setVvalue(1.0));
        }
    }

    // --------------------------------------------------------------- blocks

    private List<Node> blocks(org.commonmark.node.Node parent) {
        List<Node> nodes = new ArrayList<>();
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null;
                child = child.getNext()) {
            Node rendered = block(child);
            if (rendered != null) {
                nodes.add(rendered);
            }
        }
        return nodes;
    }

    private Node block(org.commonmark.node.Node node) {
        if (node instanceof Heading heading) {
            TextFlow flow = inline(heading);
            flow.getStyleClass().addAll("md-heading", "md-h" + heading.getLevel());
            return flow;
        }
        if (node instanceof Paragraph paragraph) {
            TextFlow flow = inline(paragraph);
            flow.getStyleClass().add("md-paragraph");
            return flow;
        }
        if (node instanceof FencedCodeBlock code) {
            return code(code.getLiteral(), code.getInfo());
        }
        if (node instanceof IndentedCodeBlock code) {
            return code(code.getLiteral(), "");
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            return list(node);
        }
        if (node instanceof BlockQuote quote) {
            VBox box = new VBox(2, blocks(quote).toArray(Node[]::new));
            box.getStyleClass().add("md-quote");
            return box;
        }
        if (node instanceof ThematicBreak) {
            Separator rule = new Separator();
            rule.getStyleClass().add("md-rule");
            return rule;
        }
        if (node instanceof Document) {
            return new VBox(2, blocks(node).toArray(Node[]::new));
        }
        // Anything else - a table, an HTML block - as its text, which is better than
        // dropping it silently.
        TextFlow flow = inline(node);
        return flow.getChildren().isEmpty() ? null : flow;
    }

    /**
     * A fenced block: coloured by the language it says it is, and named.
     *
     * <p>The colouring is the IDE's own - the same {@code Highlighter} a plugin gives the
     * editor, and the same {@code tok-} style classes - so a SQL statement in an answer
     * reads the way SQL reads in the file next door, in whichever theme is on. The name in
     * the corner is there because a block of code with no label is a guess: half the
     * snippets in a tutorial are the language being taught and half are the shell you run
     * it with, and they look identical in monospace.
     */
    private Node code(String literal, String info) {
        String body = literal.stripTrailing();
        // A folded result costs a title until somebody opens it. Every token the model streams
        // rebuilds this pane, and colouring three hundred lines nobody is looking at, forty times
        // a second, is where an answer stops arriving and starts crawling.
        return "details".equals(info) ? folded(body, info) : drawn(body, info);
    }

    private Node drawn(String body, String info) {
        String[] classes = colouring(body, Fences.highlighter(ide, info));
        VBox lines = new VBox();
        lines.getStyleClass().add("md-code-body");
        int offset = 0;
        for (String line : body.split("\n", -1)) {
            TextFlow flow = new TextFlow();
            flow.getStyleClass().add("md-code-line");
            for (Text run : runs(line, classes, offset)) {
                flow.getChildren().add(run);
            }
            lines.getChildren().add(flow);
            offset += line.length() + 1;
        }

        ScrollPane sideways = new ScrollPane(lines);
        sideways.getStyleClass().add("md-code");
        sideways.setFitToWidth(false);
        sideways.setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        sideways.setVbarPolicy(ScrollBarPolicy.NEVER);
        /* Tall enough for the block and no taller, so the outer view scrolls rather than
           each snippet: a line and a half of leading over the rows themselves. */
        int rows = body.isEmpty() ? 1 : body.split("\n", -1).length;
        sideways.setPrefHeight(Math.min(400, 20 + 16.5 * rows));
        /* Scrolling a block moves its text out from under the band drawn over it, and a highlight
           left behind on the words either side is worse than no highlight. It goes. */
        sideways.hvalueProperty().addListener((value, was, now) -> selection.clear());

        // "details" is this pane's own word for a folded tool result, not a language to label.
        Label name = new Label("details".equals(info) ? "" : Fences.label(ide, info));
        name.getStyleClass().add("md-code-lang");
        javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);

        /* A command in an answer is there to be run, and getting it out of a drawn block by hand
           means selecting it exactly, which nobody enjoys and a long line makes impossible. The
           button takes the block whole - the text as it was written, not as it was coloured. */
        javafx.scene.control.Button copy = new javafx.scene.control.Button("Copy");
        copy.getStyleClass().add("md-code-copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(body);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
            javafx.animation.PauseTransition back =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
            back.setOnFinished(done -> copy.setText("Copy"));
            back.play();
        });

        javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(name, gap, copy);
        header.getStyleClass().add("md-code-header");
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox block = new VBox(header, sideways);
        block.getStyleClass().add("md-code-block");
        // Out of the way until the pointer is on the block, so an answer reads as an answer.
        copy.visibleProperty().bind(block.hoverProperty());
        return block;
    }

    /**
     * What a tool found, shut until somebody wants it.
     *
     * <p>A step in the transcript says what was done and how much came back - "Reading 3 files
     * (241 lines)" - which is the right amount to read while the agent works and no use when the
     * 241 lines are the reason the answer is wrong. They go in here: closed, so the transcript
     * stays a list of steps, and one click from being read.
     */
    private Node folded(String body, String info) {
        long lines = body.isBlank() ? 0 : body.lines().count();
        javafx.scene.control.TitledPane details = new javafx.scene.control.TitledPane(
                lines + (lines == 1 ? " line" : " lines") + " - click to read",
                new javafx.scene.layout.Region());
        details.setAnimated(false);
        details.getStyleClass().add("md-details");
        details.setExpanded(false);
        // Built the first time it is opened, and only then.
        details.expandedProperty().addListener((value, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                fill(details, body, info);
            }
        });
        /* Opened by its contents, not by where it sits. Every token the model streams redraws
           this whole pane from scratch, so a result the reader had opened would shut itself a
           quarter of a second later - and keeping a list by position would open the wrong one
           the moment another project's conversation came up. */
        int what = body.hashCode();
        boolean open = opened.contains(what);
        if (open) {
            /* Filled before it is told to open, not by the listener that watches for opening: a
               TitledPane starts expanded, so setting it expanded changes nothing and tells
               nobody - which showed up as a result that had been open coming back empty after
               the next token was streamed. */
            fill(details, body, info);
        }
        details.setExpanded(open);
        details.expandedProperty().addListener((value, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                opened.add(what);
            } else {
                opened.remove(what);
            }
        });
        return details;
    }

    /** Draws what is inside a fold, once, the first time anybody wants to see it. */
    private void fill(javafx.scene.control.TitledPane details, String body, String info) {
        if (!(details.getContent() instanceof VBox)) {
            details.setContent(drawn(body, info));
        }
    }

    /**
     * The style class for every character of the block, or null where there is none.
     *
     * <p>The whole block is tokenized at once and the answer kept per character, because
     * the drawing is per line and the colouring is not: a block comment or a triple-quoted
     * string runs across lines, and a highlighter handed one line at a time would end it
     * at every newline.
     */
    private static String[] colouring(String body, Highlighter highlighter) {
        String[] classes = new String[body.length()];
        if (highlighter == Highlighter.NONE || body.isEmpty()) {
            return classes;
        }
        List<Token> tokens;
        try {
            tokens = highlighter.tokenize(body);
        } catch (RuntimeException e) {
            // A tokenizer that trips over a snippet leaves it uncoloured, which is the
            // correct amount of consequence for that.
            return classes;
        }
        for (Token token : tokens) {
            String styleClass = token.type().styleClass();
            for (int at = Math.max(0, token.start());
                    at < Math.min(body.length(), token.end()); at++) {
                classes[at] = styleClass;
            }
        }
        return classes;
    }

    /**
     * One line as coloured runs.
     *
     * <p>Line by line rather than one flow for the whole block, because a {@code TextFlow}
     * asked how wide it wants to be answers with the sum of everything in it - one very
     * long line - and the block would then scroll sideways into empty space.
     */
    private static List<Text> runs(String line, String[] classes, int offset) {
        List<Text> runs = new ArrayList<>();
        if (line.isEmpty()) {
            // Something, or the line has no height and the block loses its shape.
            runs.add(run(" "));
            return runs;
        }
        int at = 0;
        while (at < line.length()) {
            String styleClass = classAt(classes, offset + at);
            int end = at + 1;
            while (end < line.length()
                    && java.util.Objects.equals(classAt(classes, offset + end), styleClass)) {
                end++;
            }
            Text text = run(line.substring(at, end));
            if (styleClass != null) {
                text.getStyleClass().add(styleClass);
            }
            runs.add(text);
            at = end;
        }
        return runs;
    }

    private static String classAt(String[] classes, int at) {
        return at >= 0 && at < classes.length ? classes[at] : null;
    }

    private static Text run(String literal) {
        Text text = new Text(literal);
        text.getStyleClass().add("md-code-run");
        return text;
    }

    private Node list(org.commonmark.node.Node listNode) {
        VBox items = new VBox(2);
        items.getStyleClass().add("md-list");
        boolean ordered = listNode instanceof OrderedList;
        int number = ordered ? ((OrderedList) listNode).getStartNumber() : 0;
        for (org.commonmark.node.Node item = listNode.getFirstChild(); item != null;
                item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            Label bullet = new Label(ordered ? (number++) + "." : "•");
            bullet.getStyleClass().add("md-bullet");
            bullet.setMinWidth(ordered ? 22 : 14);
            VBox body = new VBox(2, blocks(item).toArray(Node[]::new));
            body.setFillWidth(true);
            HBox.setHgrow(body, Priority.ALWAYS);
            HBox row = new HBox(2, bullet, body);
            items.getChildren().add(row);
        }
        return items;
    }

    // --------------------------------------------------------------- inline

    /** One paragraph's worth of styled runs. */
    private TextFlow inline(org.commonmark.node.Node parent) {
        TextFlow flow = new TextFlow();
        appendInline(flow, parent, "");
        return flow;
    }

    private void appendInline(TextFlow flow, org.commonmark.node.Node parent, String style) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null;
                child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text text) {
                flow.getChildren().add(run(text.getLiteral(), style));
            } else if (child instanceof Code code) {
                flow.getChildren().add(run(code.getLiteral(), "md-inline-code"));
            } else if (child instanceof StrongEmphasis) {
                appendInline(flow, child, "md-strong");
            } else if (child instanceof Emphasis) {
                appendInline(flow, child, "md-emphasis");
            } else if (child instanceof Link) {
                appendInline(flow, child, "md-link");
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                flow.getChildren().add(run(" ", style));
            } else {
                appendInline(flow, child, style);
            }
        }
    }

    private Text run(String literal, String style) {
        Text text = new Text(literal);
        text.getStyleClass().add("md-text");
        if (!style.isEmpty()) {
            text.getStyleClass().add(style);
        }
        return text;
    }
}
