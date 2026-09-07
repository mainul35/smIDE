package com.smide.editor;

import com.smide.api.debug.Breakpoint;
import com.smide.api.debug.Breakpoints;
import com.smide.api.editor.LineAnnotations;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.nio.file.Path;
import java.util.function.IntFunction;

/**
 * The strip down the left of the editor: a breakpoint column and the line number.
 *
 * <p>Clicking anywhere in the breakpoint column toggles a breakpoint on that line, the
 * way every IDE does it. The column is always there, showing a faint circle under the
 * pointer, so it is discoverable without a menu.
 *
 * <p>RichTextFX asks for a graphic per visible paragraph and reuses nothing, so each
 * node is built fresh and must stay cheap.
 */
public final class GutterFactory implements IntFunction<Node> {

    /** Width of the breakpoint column, and the hit zone the editor tests against. */
    public static final double COLUMN_WIDTH = 16;

    private final CodeArea area;
    private final Breakpoints breakpoints;
    private final Path file;
    private final IntFunction<Node> lineNumbers;
    /** The line the debugger is stopped on, or -1. */
    private int executionLine = -1;
    /** Per-line text shown left of everything else - blame, when it is switched on. */
    private LineAnnotations annotations;

    public GutterFactory(CodeArea area, Breakpoints breakpoints, Path file) {
        this.area = area;
        this.breakpoints = breakpoints;
        this.file = file;
        this.lineNumbers = LineNumberFactory.get(area);
    }

    /** Marks the line the debugger has stopped on; -1 clears it. */
    public void setExecutionLine(int line) {
        this.executionLine = line;
    }

    public int executionLine() {
        return executionLine;
    }

    /** Shows a per-line column beside the numbers, or clears it when null. */
    public void setAnnotations(LineAnnotations annotations) {
        this.annotations = annotations;
    }

    public LineAnnotations annotations() {
        return annotations;
    }

    @Override
    public Node apply(int paragraph) {
        StackPane marker = new StackPane();
        marker.setMinWidth(COLUMN_WIDTH);
        marker.setPrefWidth(COLUMN_WIDTH);
        marker.setMaxWidth(COLUMN_WIDTH);
        marker.setAlignment(Pos.CENTER);
        marker.getStyleClass().add("breakpoint-column");
        /* Pickable and tagged with its line. Clicks are not handled here - the area
           filters mouse presses to place the caret before any child sees them - so the
           editor's filter finds this node through the event's pick result instead. */
        marker.setPickOnBounds(true);
        marker.setUserData(paragraph);

        Breakpoint breakpoint = breakpoints.at(file, paragraph).orElse(null);
        if (breakpoint != null) {
            Circle dot = new Circle(4.5);
            dot.getStyleClass().add(breakpoint.enabled() ? "breakpoint-dot" : "breakpoint-dot-disabled");
            Tooltip.install(marker, new Tooltip(breakpoint.condition() == null
                    ? "Breakpoint on line " + (paragraph + 1) + ". Click to remove."
                    : "Breakpoint: " + breakpoint.condition()));
            marker.getChildren().add(dot);
        } else {
            // A hollow circle that only appears on hover, so the column reads as clickable.
            Circle ghost = new Circle(4.5);
            ghost.getStyleClass().add("breakpoint-ghost");
            ghost.setVisible(false);
            marker.setOnMouseEntered(e -> ghost.setVisible(true));
            marker.setOnMouseExited(e -> ghost.setVisible(false));
            marker.getChildren().add(ghost);
        }

        if (paragraph == executionLine) {
            Polygon arrow = new Polygon(0, -4.5, 7, 0, 0, 4.5);
            arrow.getStyleClass().add("execution-arrow");
            arrow.setFill(Color.TRANSPARENT);
            marker.getChildren().add(arrow);
        }

        Node number = lineNumbers.apply(paragraph);
        HBox row = annotations == null
                ? new HBox(marker, number)
                : new HBox(annotation(paragraph), marker, number);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("gutter");
        /* The whole gutter toggles a breakpoint, line number included, which is what
           every IDE does and what a 16-pixel column alone was too small to offer. */
        row.setPickOnBounds(true);
        row.setUserData(paragraph);
        if (paragraph == executionLine) {
            row.getStyleClass().add("gutter-executing");
        }
        return row;
    }

    /**
     * One cell of the annotation column.
     *
     * <p>Sized from the widest value rather than its own text, so the code does not
     * shift left and right as the gutter scrolls past a short name.
     */
    private Node annotation(int paragraph) {
        Label label = new Label(annotations.text(paragraph));
        label.getStyleClass().add("line-annotation");
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setPrefWidth(width());
        label.setMaxWidth(Region.USE_PREF_SIZE);
        String tip = annotations.tooltip(paragraph);
        if (tip != null && !tip.isBlank()) {
            Tooltip.install(label, new Tooltip(tip));
        }
        label.setOnMouseClicked(e -> {
            annotations.clicked(paragraph);
            e.consume();
        });
        return label;
    }

    /**
     * Measures the widest annotation once.
     *
     * <p>A bare {@link Text} reports its bounds without being in a scene, which a
     * paragraph graphic being built during layout has no business creating. The font
     * is the one the stylesheet gives {@code .line-annotation}.
     */
    private double width() {
        if (annotationWidth <= 0) {
            Text probe = new Text(annotations.widest());
            probe.setFont(Font.font(ANNOTATION_FONT, ANNOTATION_SIZE));
            annotationWidth = probe.getLayoutBounds().getWidth() + 14;
        }
        return annotationWidth;
    }

    private static final String ANNOTATION_FONT = "Consolas";
    private static final double ANNOTATION_SIZE = 11;
    private double annotationWidth;

    /** Toggles the breakpoint on a line, from the editor's mouse filter. */
    public void toggleAt(int line) {
        breakpoints.toggle(file, line);
    }

    /**
     * Repaints the gutter.
     *
     * <p>A method reference rather than {@code this}: the factory is an ObjectProperty,
     * and setting it to the value it already holds fires no change, so the graphics were
     * never rebuilt and a new breakpoint stayed invisible until the file was reopened.
     */
    public void refresh() {
        area.setParagraphGraphicFactory(this::apply);
    }

    /** A label used when there is no line-number factory, for tests. */
    static Node placeholder(int paragraph) {
        return new Label(String.valueOf(paragraph + 1));
    }
}
