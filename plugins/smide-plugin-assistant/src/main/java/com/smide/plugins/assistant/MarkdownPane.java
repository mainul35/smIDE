package com.smide.plugins.assistant;

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
    private boolean follow = true;

    MarkdownPane() {
        content.getStyleClass().add("md-pane");
        content.setPadding(new Insets(10, 12, 18, 12));
        content.setFillWidth(true);
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("md-scroll");
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
        content.getChildren().setAll(blocks(PARSER.parse(markdown == null ? "" : markdown)));
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
            return code(code.getLiteral());
        }
        if (node instanceof IndentedCodeBlock code) {
            return code(code.getLiteral());
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

    /** A fenced block: monospace, its own ground, and scrolling sideways rather than wrapping. */
    private Node code(String literal) {
        Label text = new Label(literal.stripTrailing());
        text.getStyleClass().add("md-code-text");
        ScrollPane sideways = new ScrollPane(text);
        sideways.getStyleClass().add("md-code");
        sideways.setFitToWidth(false);
        sideways.setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        sideways.setVbarPolicy(ScrollBarPolicy.NEVER);
        // Tall enough for the block, so the outer view scrolls rather than each snippet.
        sideways.setPrefHeight(Math.min(400, 20 + 17.0 * (literal.split("\n").length + 1)));
        return sideways;
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
