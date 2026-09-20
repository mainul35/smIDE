package com.smide.plugins.git;

import com.smide.api.ui.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Two versions of a file beside each other, aligned line for line.
 *
 * <p>A unified diff answers "what changed"; this answers "what does it look like now
 * against then", which is the question being asked when a branch is compared. The two
 * texts are aligned by the same algorithm git uses, blank filler lines are inserted
 * where one side has nothing, and the panes scroll as one so the eye stays on a row.
 */
public final class SideBySideDiff extends BorderPane {

    private final StyleClassedTextArea left = new StyleClassedTextArea();
    private final StyleClassedTextArea right = new StyleClassedTextArea();
    private final Label leftTitle = new Label();
    private final Label rightTitle = new Label();
    private final Label summary = new Label();
    /** Rows where the two sides differ, for the next and previous buttons. */
    private final List<Integer> changes = new ArrayList<>();
    private int currentChange = -1;

    public SideBySideDiff(Theme theme) {
        for (StyleClassedTextArea area : List.of(left, right)) {
            area.setEditable(false);
            area.setWrapText(false);
            area.getStyleClass().addAll("diff-area", "diff-side");
        }
        leftTitle.getStyleClass().add("diff-side-title");
        rightTitle.getStyleClass().add("diff-side-title");
        summary.getStyleClass().add("muted-small");

        /* One scrollbar's worth of movement, two panes: comparing is looking across a
           row, and that only works while the rows stay level. */
        left.estimatedScrollYProperty().bindBidirectional(right.estimatedScrollYProperty());
        left.estimatedScrollXProperty().bindBidirectional(right.estimatedScrollXProperty());

        HBox panes = new HBox(new VBox(leftTitle, grow(new VirtualizedScrollPane<>(left))),
                new VBox(rightTitle, grow(new VirtualizedScrollPane<>(right))));
        for (Node child : panes.getChildren()) {
            HBox.setHgrow(child, Priority.ALWAYS);
            ((VBox) child).setFillWidth(true);
            ((VBox) child).setMinWidth(0);
        }
        setCenter(panes);
        setTop(toolbar());

        getStylesheets().add(theme.stylesheet());
        getStylesheets().add(SideBySideDiff.class.getResource("/css/git.css").toExternalForm());
        theme.style(this);
    }

    private static Node grow(Node node) {
        VBox.setVgrow(node, Priority.ALWAYS);
        return node;
    }

    private Node toolbar() {
        Button previous = icon("fth-chevron-up", "Previous difference", () -> step(-1));
        Button next = icon("fth-chevron-down", "Next difference", () -> step(1));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(4, previous, next, gap, summary);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 6));
        return bar;
    }

    private static Button icon(String literal, String tip, Runnable action) {
        Button button = new Button();
        button.setGraphic(new FontIcon(literal));
        button.getStyleClass().add("icon-button");
        button.setTooltip(com.smide.api.ui.Tooltips.of(tip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Shows two versions; either may be empty, which is how an added or deleted file reads. */
    public void setContent(String leftLabel, String leftText, String rightLabel, String rightText) {
        leftTitle.setText(leftLabel);
        rightTitle.setText(rightLabel);
        left.clear();
        right.clear();
        changes.clear();
        currentChange = -1;

        RawText a = new RawText(leftText.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(rightText.getBytes(StandardCharsets.UTF_8));
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);

        List<String> leftLines = new ArrayList<>();
        List<String> rightLines = new ArrayList<>();
        List<String> leftStyles = new ArrayList<>();
        List<String> rightStyles = new ArrayList<>();
        List<Integer> leftNumbers = new ArrayList<>();
        List<Integer> rightNumbers = new ArrayList<>();
        int[] counts = new int[3]; // added, removed, changed

        int ai = 0;
        int bi = 0;
        for (Edit edit : edits) {
            while (ai < edit.getBeginA()) {
                row(leftLines, leftStyles, leftNumbers, a.getString(ai), "diff-line-same", ai + 1);
                row(rightLines, rightStyles, rightNumbers, b.getString(bi), "diff-line-same", bi + 1);
                ai++;
                bi++;
            }
            int inA = edit.getEndA() - edit.getBeginA();
            int inB = edit.getEndB() - edit.getBeginB();
            for (int k = 0; k < Math.max(inA, inB); k++) {
                boolean hasLeft = k < inA;
                boolean hasRight = k < inB;
                String style = hasLeft && hasRight ? "diff-line-change"
                        : hasLeft ? "diff-line-remove" : "diff-line-add";
                changes.add(leftLines.size());
                counts[hasLeft && hasRight ? 2 : hasLeft ? 1 : 0]++;
                row(leftLines, leftStyles, leftNumbers,
                        hasLeft ? a.getString(edit.getBeginA() + k) : "",
                        hasLeft ? style : "diff-line-filler", hasLeft ? edit.getBeginA() + k + 1 : 0);
                row(rightLines, rightStyles, rightNumbers,
                        hasRight ? b.getString(edit.getBeginB() + k) : "",
                        hasRight ? style : "diff-line-filler", hasRight ? edit.getBeginB() + k + 1 : 0);
            }
            ai = edit.getEndA();
            bi = edit.getEndB();
        }
        while (ai < a.size() && bi < b.size()) {
            row(leftLines, leftStyles, leftNumbers, a.getString(ai), "diff-line-same", ai + 1);
            row(rightLines, rightStyles, rightNumbers, b.getString(bi), "diff-line-same", bi + 1);
            ai++;
            bi++;
        }

        fill(left, leftLines, leftStyles, leftNumbers);
        fill(right, rightLines, rightStyles, rightNumbers);
        summary.setText(changes.isEmpty() ? "The two versions are identical"
                : counts[2] + " changed, " + counts[0] + " added, " + counts[1] + " removed");
    }

    private static void row(List<String> lines, List<String> styles, List<Integer> numbers,
                            String text, String style, int number) {
        lines.add(text);
        styles.add(style);
        numbers.add(number);
    }

    private static void fill(StyleClassedTextArea area, List<String> lines, List<String> styles,
                             List<Integer> numbers) {
        area.replaceText(String.join("\n", lines));
        for (int i = 0; i < styles.size() && i < area.getParagraphs().size(); i++) {
            area.setParagraphStyle(i, List.of(styles.get(i)));
        }
        int width = String.valueOf(Math.max(1, numbers.size())).length();
        area.setParagraphGraphicFactory(index -> {
            int number = index < numbers.size() ? numbers.get(index) : 0;
            Label label = new Label(number == 0 ? " ".repeat(width) : pad(number, width));
            label.getStyleClass().add("diff-lineno");
            return label;
        });
        area.moveTo(0);
    }

    private static String pad(int number, int width) {
        String text = String.valueOf(number);
        return " ".repeat(Math.max(0, width - text.length())) + text;
    }

    /** Moves to the next or previous run of differing lines. */
    private void step(int direction) {
        if (changes.isEmpty()) {
            return;
        }
        currentChange = currentChange < 0 && direction < 0 ? changes.size() - 1
                : Math.floorMod(currentChange + direction, changes.size());
        int row = changes.get(currentChange);
        left.showParagraphAtTop(Math.max(0, row - 3));
        right.showParagraphAtTop(Math.max(0, row - 3));
    }
}
