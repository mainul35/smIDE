package com.smide.editor;

import com.smide.api.debug.Breakpoint;
import com.smide.api.debug.Breakpoints;
import com.smide.api.editor.LineAnnotations;
import com.smide.theme.ThemeManager;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The strip down the left of the editor: a breakpoint column and the line number.
 *
 * <p>Clicking anywhere in the breakpoint column toggles a breakpoint on that line, the
 * way every IDE does it. The column is always there, showing a faint circle under the
 * pointer, so it is discoverable without a menu. Right-clicking it opens the menu that
 * does the rest: enable, disable, remove.
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
    /** The breakpoint menu on screen, if any, so a second right click replaces it. */
    private ContextMenu menu;

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
            // A diamond when it has a condition: a line that may not stop should not look like one that will.
            Shape dot = breakpoint.condition() == null
                    ? new Circle(4.5)
                    : new Polygon(0, -5.5, 5.5, 0, 0, 5.5, -5.5, 0);
            dot.getStyleClass().add(breakpoint.enabled() ? "breakpoint-dot" : "breakpoint-dot-disabled");
            Tooltip.install(marker, new Tooltip(tooltip(breakpoint)));
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

    private static String tooltip(Breakpoint breakpoint) {
        int line = breakpoint.line() + 1;
        String when = breakpoint.condition() == null ? "" : "\nStops only when: " + breakpoint.condition();
        if (!breakpoint.enabled()) {
            return "Disabled breakpoint on line " + line + " - the debugger passes it by."
                    + " Right-click to enable." + when;
        }
        return (breakpoint.condition() == null
                ? "Breakpoint on line " + line + ". Click to remove; right-click to disable."
                : "Conditional breakpoint on line " + line + ". Right-click to change the condition.") + when;
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

    // Asked for by name, so it has to be one this machine really has: Font.font falls
    // back to the default *proportional* face for a name it does not know, which in a
    // gutter of blame dates is instantly obvious.
    private static final String ANNOTATION_FONT = com.smide.ui.Fonts.monospace();
    private static final double ANNOTATION_SIZE = 11;
    private double annotationWidth;

    /** Toggles the breakpoint on a line, from the editor's mouse filter. */
    public void toggleAt(int line) {
        breakpoints.toggle(file, line);
    }

    /**
     * Opens the breakpoint menu for a line, from the editor's context-menu filter.
     *
     * @param owner the node the menu belongs to - the area, which outlives the gutter
     *              graphic that was clicked, since every change rebuilds those
     */
    public void showMenu(Node owner, int line, double screenX, double screenY) {
        if (menu != null) {
            menu.hide();
        }
        menu = menuFor(line);
        menu.show(owner, screenX, screenY);
    }

    /**
     * What a right click on a line offers.
     *
     * <p>Built on demand for the line clicked, so it shows the breakpoint as it is now -
     * one a debug session or another editor on the same file changed a moment ago
     * included. Disabling keeps the breakpoint where it is, which is the point of it: a
     * line you will want to stop on again, but not on this pass.
     */
    ContextMenu menuFor(int line) {
        ContextMenu built = new ContextMenu();
        Breakpoint breakpoint = breakpoints.at(file, line).orElse(null);
        if (breakpoint == null) {
            MenuItem add = new MenuItem("Add Breakpoint");
            add.setOnAction(e -> breakpoints.add(new Breakpoint(file, line)));
            built.getItems().add(add);
        } else {
            CheckMenuItem enabled = new CheckMenuItem("Enabled");
            enabled.setSelected(breakpoint.enabled());
            // The check has already flipped by the time the action runs.
            enabled.setOnAction(e -> breakpoints.update(breakpoint.withEnabled(enabled.isSelected())));
            MenuItem condition = new MenuItem("Condition...");
            condition.setOnAction(e -> askCondition(breakpoint));
            MenuItem remove = new MenuItem("Remove Breakpoint");
            remove.setOnAction(e -> breakpoints.remove(file, line));
            built.getItems().addAll(enabled, condition, remove);
        }
        List<Breakpoint> inFile = breakpoints.inFile(file);
        if (!inFile.isEmpty()) {
            boolean anyEnabled = inFile.stream().anyMatch(Breakpoint::enabled);
            MenuItem every = new MenuItem(anyEnabled
                    ? "Disable All in This File" : "Enable All in This File");
            every.setOnAction(e -> inFile.forEach(b -> breakpoints.update(b.withEnabled(!anyEnabled))));
            MenuItem removeAll = new MenuItem("Remove All in This File");
            removeAll.setOnAction(e -> inFile.forEach(b -> breakpoints.remove(file, b.line())));
            built.getItems().addAll(new SeparatorMenuItem(), every, removeAll);
        }
        return built;
    }

    /**
     * Asks for the condition a breakpoint stops on.
     *
     * <p>Empty means always stop, which is how a condition is taken off; Cancel leaves it as
     * it was. The text is not checked here - the editor has no debugger to check it with,
     * and a condition can only be judged in a frame - so a mistake shows when the line is
     * reached: the debugger stops there and says what was wrong, rather than skipping it.
     */
    private void askCondition(Breakpoint breakpoint) {
        TextInputDialog dialog = new TextInputDialog(breakpoint.condition() == null ? "" : breakpoint.condition());
        dialog.setTitle("Breakpoint Condition");
        dialog.setHeaderText("Stop at " + breakpoint.label() + " only when this is true.\n"
                + "For example  i == 20  or  name.equals(\"x\")  - leave it empty to always stop.");
        dialog.setContentText("Condition");
        dialog.getEditor().setPrefColumnCount(36);
        Scene owner = area.getScene();
        if (owner != null) {
            dialog.initOwner(owner.getWindow());
            // Styled like the window it came from, as the theme styles any window: its
            // stylesheet, and the dark class when that window has it.
            Scene scene = dialog.getDialogPane().getScene();
            scene.getStylesheets().addAll(owner.getStylesheets());
            if (owner.getRoot().getStyleClass().contains(ThemeManager.DARK_CLASS)) {
                scene.getRoot().getStyleClass().add(ThemeManager.DARK_CLASS);
            }
        }
        // Applied to the breakpoint as it is now, in case it was enabled or disabled meanwhile.
        dialog.showAndWait().ifPresent(text -> breakpoints.at(file, breakpoint.line())
                .ifPresent(current -> breakpoints.update(current.withCondition(text.strip()))));
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
