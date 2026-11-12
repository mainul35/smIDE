package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The graph, drawn, beside the file that builds it.
 *
 * <p>A graph is the one thing about this kind of code that the code does not show: the file says
 * a node is added here and an edge there, and the shape those calls make - the branch, the loop,
 * the node nothing reaches - is in the reader's head or on a whiteboard. This draws it from the
 * calls themselves, as they are typed, so it is right while the file is still being written and
 * wrong in the same way the file is.
 *
 * <p>It is drawn from what is written rather than from a running graph on purpose. A picture
 * obtained by importing the module and asking it to draw itself is a truer picture, but it costs
 * an interpreter, an import of everything the graph imports, and a round trip per keystroke -
 * and it cannot say where a node is written, which is the thing that makes the picture worth
 * clicking. Where an edge is decided at run time and cannot be read, the window says so rather
 * than pretending the graph is smaller than it is.
 */
public final class GraphToolWindow implements ToolWindowFactory {

    public static final String ID = "langgraph.graph";

    private static final double BOX_WIDTH = 132;
    private static final double BOX_HEIGHT = 34;
    private static final double GAP_X = 18;
    private static final double GAP_Y = 46;
    private static final double PADDING = 16;

    private final Ide ide;
    private final Pane canvas = new Pane();
    private final ComboBox<String> graphs = new ComboBox<>();
    private final Label note = new Label();
    private final Label empty = new Label("Open a file that builds a graph.");
    private final StackPane middle = new StackPane();
    private final BorderPane content = new BorderPane();

    /** The editor being watched, and the listener watching it, so it can be let go of. */
    private TextEditor watching;
    private Consumer<String> watcher;
    /** The cells of the notebook being looked at, or null when it is a plain Python file. */
    private Notebook.Code notebook;
    private List<GraphChecks.Graph> current = List.of();
    private ToolWindowContext context;

    public GraphToolWindow(Ide ide) {
        this.ide = ide;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Graph";
    }

    @Override
    public String iconLiteral() {
        return "mdi2g-graph-outline";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.RIGHT;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        empty.getStyleClass().add("empty-hint");
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        graphs.setMaxWidth(Double.MAX_VALUE);
        graphs.valueProperty().addListener((o, was, now) -> draw());
        HBox.setHgrow(graphs, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(new Group(canvas));
        scroll.setPannable(true);
        scroll.setFitToWidth(false);
        scroll.getStyleClass().add("graph-scroll");
        middle.getChildren().addAll(empty, scroll);
        middle.setAlignment(Pos.CENTER);

        HBox top = new HBox(6, graphs);
        top.setPadding(new Insets(8, 8, 4, 8));
        content.setTop(top);
        content.setCenter(middle);
        content.setBottom(note);
        BorderPane.setMargin(note, new Insets(4, 8, 8, 8));

        ide.editors().addActiveListener(editor -> follow(editor.orElse(null)));
        ide.events().subscribe(Events.ThemeChanged.class, changed -> draw());
        follow(ide.editors().active().orElse(null));
        return content;
    }

    // ------------------------------------------------------- following the editor

    /** Watches one editor's text, and stops watching whatever it was watching before. */
    private void follow(Editor editor) {
        if (watching != null && watcher != null) {
            watching.removeTextListener(watcher);
        }
        watching = null;
        watcher = null;
        TextEditor text = editor == null ? null : editor.asText().orElse(null);
        if (text == null || !(com.smide.api.util.ProjectFiles.hasExtension(editor.path(), "py")
                || Notebook.isNotebook(editor.path()))) {
            // Keep the last graph up: looking at a test file should not empty the window.
            return;
        }
        watching = text;
        watcher = whole -> read(whole);
        text.addTextListener(watcher);
        read(text.text());
    }

    /** Re-reads the graphs of a file, keeping the one being looked at selected if it is still there. */
    private void read(String contents) {
        // A notebook is read as the Python in its code cells; anything else is Python already.
        notebook = watching != null && Notebook.isNotebook(watching.path())
                ? Notebook.code(contents) : null;
        String python = notebook == null ? contents : notebook.text();
        List<GraphChecks.Graph> found = GraphChecks.graphsIn(python);
        current = found;
        List<String> names = found.stream().map(graph -> graph.variable).toList();
        String chosen = graphs.getValue();
        if (!names.equals(graphs.getItems())) {
            graphs.getItems().setAll(names);
        }
        graphs.setVisible(names.size() > 1);
        graphs.setManaged(names.size() > 1);
        if (names.isEmpty()) {
            graphs.setValue(null);
        } else if (chosen == null || !names.contains(chosen)) {
            graphs.setValue(names.get(0));
        }
        draw();
    }

    // ------------------------------------------------------------------ the drawing

    private Optional<GraphChecks.Graph> chosen() {
        String name = graphs.getValue();
        return current.stream().filter(graph -> graph.variable.equals(name)).findFirst()
                .or(() -> current.isEmpty() ? Optional.empty() : Optional.of(current.get(0)));
    }

    private void draw() {
        canvas.getChildren().clear();
        GraphChecks.Graph graph = chosen().orElse(null);
        GraphLayout.Placed placed = graph == null ? null : GraphLayout.of(graph);
        boolean anything = placed != null && !placed.isEmpty();
        empty.setVisible(!anything);
        middle.getChildren().get(1).setVisible(anything);
        note.setText("");
        if (!anything) {
            if (context != null) {
                context.setTitle("Graph");
            }
            return;
        }
        double width = PADDING * 2 + placed.lanes() * BOX_WIDTH + (placed.lanes() - 1) * GAP_X;
        double height = PADDING * 2 + placed.rows() * BOX_HEIGHT + (placed.rows() - 1) * GAP_Y;
        canvas.setMinSize(width, height);
        canvas.setPrefSize(width, height);

        for (GraphLayout.Link link : placed.links()) {
            drawLink(placed, link, width);
        }
        for (GraphLayout.Box box : placed.boxes()) {
            drawBox(placed, box, width);
        }
        long nodes = placed.boxes().stream().filter(b -> b.kind() == GraphLayout.Kind.NODE).count();
        if (context != null) {
            context.setTitle("Graph (" + nodes + ")");
        }
        List<String> notes = new ArrayList<>();
        if (notebook != null) {
            notes.add("Read from this notebook's code cells, joined in the order they run."
                    + " Problems in a graph are only underlined in a .py file - a notebook has no editor"
                    + " to underline them in.");
        }
        if (placed.unknownTargets()) {
            notes.add("Some edges are decided while the graph runs, and are not drawn:"
                    + " a router with no mapping, or a name that comes from a variable.");
        }
        note.setText(String.join(" ", notes));
    }

    /** Where a box sits: rows down the window, its row's boxes centred across it. */
    private double x(GraphLayout.Placed placed, GraphLayout.Box box, double width) {
        int across = placed.width(box.row());
        double row = across * BOX_WIDTH + (across - 1) * GAP_X;
        return (width - row) / 2 + box.lane() * (BOX_WIDTH + GAP_X);
    }

    private double y(GraphLayout.Box box) {
        return PADDING + box.row() * (BOX_HEIGHT + GAP_Y);
    }

    private void drawBox(GraphLayout.Placed placed, GraphLayout.Box box, double width) {
        double left = x(placed, box, width);
        double top = y(box);
        boolean ends = box.kind() != GraphLayout.Kind.NODE;
        Rectangle shape = new Rectangle(BOX_WIDTH, BOX_HEIGHT);
        shape.setArcWidth(ends ? BOX_HEIGHT : 10);
        shape.setArcHeight(ends ? BOX_HEIGHT : 10);
        shape.setFill(color(ends ? "surface-alt" : "surface"));
        shape.setStroke(color(box.reachable() ? ends ? "border" : "accent" : "warning"));
        if (!box.reachable()) {
            // Dashed, like the problem said about it in the file: nothing leads here.
            shape.getStrokeDashArray().setAll(4.0, 4.0);
        }

        Text label = new Text(trimmed(box.label()));
        label.setFill(color(box.reachable() ? "text" : "text-muted"));
        StackPane group = new StackPane(shape, label);
        group.setLayoutX(left);
        group.setLayoutY(top);
        group.setPrefSize(BOX_WIDTH, BOX_HEIGHT);

        if (box.kind() == GraphLayout.Kind.NODE) {
            Tooltip tip = new Tooltip(box.name() + " - " + (box.reachable() ? where(box) : "nothing leads to it"));
            tip.setShowDelay(Duration.millis(300));
            Tooltip.install(group, tip);
            group.setCursor(javafx.scene.Cursor.HAND);
            group.setOnMouseClicked(e -> goTo(box));
        }
        canvas.getChildren().add(group);
    }

    private void drawLink(GraphLayout.Placed placed, GraphLayout.Link link, double width) {
        GraphLayout.Box from = placed.box(link.from());
        GraphLayout.Box to = placed.box(link.to());
        if (from == null || to == null) {
            return;
        }
        Color stroke = color(link.conditional() ? "text-muted" : "border");
        double fromX = x(placed, from, width) + BOX_WIDTH / 2;
        double toX = x(placed, to, width) + BOX_WIDTH / 2;
        if (link.self()) {
            // A node that can run again: a small loop out of the right side and back in.
            double right = x(placed, from, width) + BOX_WIDTH;
            double middleY = y(from) + BOX_HEIGHT / 2;
            QuadCurve loop = new QuadCurve(right, middleY - 8, right + 34, middleY, right, middleY + 8);
            loop.setFill(null);
            loop.setStroke(stroke);
            dash(loop, link.conditional());
            canvas.getChildren().add(loop);
            arrow(right + 2, middleY + 8, 200, stroke);
            return;
        }
        if (link.back()) {
            // Back up the page, bowed out to the left so it is not mistaken for a forward edge.
            double fromY = y(from) + BOX_HEIGHT / 2;
            double toY = y(to) + BOX_HEIGHT / 2;
            double bow = Math.min(x(placed, from, width), x(placed, to, width)) - 22;
            QuadCurve back = new QuadCurve(x(placed, from, width), fromY, bow, (fromY + toY) / 2,
                    x(placed, to, width), toY);
            back.setFill(null);
            back.setStroke(stroke);
            dash(back, link.conditional());
            canvas.getChildren().add(back);
            arrow(x(placed, to, width), toY, 0, stroke);
            return;
        }
        double fromY = y(from) + BOX_HEIGHT;
        double toY = y(to) - 7;
        Line line = new Line(fromX, fromY, toX, toY);
        line.setStroke(stroke);
        dash(line, link.conditional());
        canvas.getChildren().add(line);
        arrow(toX, toY, Math.toDegrees(Math.atan2(toY - fromY, toX - fromX)) - 90, stroke);
    }

    /** A conditional edge is dashed: whether it is taken is decided while the graph runs. */
    private static void dash(javafx.scene.shape.Shape shape, boolean conditional) {
        shape.setStrokeWidth(1.4);
        if (conditional) {
            shape.getStrokeDashArray().setAll(5.0, 4.0);
        }
    }

    private void arrow(double x, double y, double degrees, Color stroke) {
        Polygon head = new Polygon(0, 0, -4.5, -7.5, 4.5, -7.5);
        head.setFill(stroke);
        head.setLayoutX(x);
        head.setLayoutY(y + 7);
        head.setRotate(degrees);
        canvas.getChildren().add(head);
    }

    private Color color(String token) {
        return Color.web(ide.theme().color(token));
    }

    private static String trimmed(String label) {
        return label.length() <= 16 ? label : label.substring(0, 15) + "…";
    }

    // ------------------------------------------------------------- going to the code

    /** What clicking a node will do, as the tooltip says it. */
    private String where(GraphLayout.Box box) {
        if (notebook == null) {
            return "click to go to it";
        }
        return cellOf(box).map(cell -> "written in cell " + (cell.index() + 1) + " - click to go to it")
                .orElse("written in a cell this file no longer points at");
    }

    /** The cell a node was written in, when the file being looked at is a notebook. */
    private Optional<Notebook.Cell> cellOf(GraphLayout.Box box) {
        if (notebook == null || box.offset() < 0) {
            return Optional.empty();
        }
        return notebook.cellAt(python(box)).filter(cell -> cell.at() >= 0);
    }

    /** Where this node is in the Python that was read, which for a notebook is the joined cells. */
    private int python(GraphLayout.Box box) {
        String code = notebook == null ? watching == null ? "" : watching.text() : notebook.text();
        int definition = Offsets.definition(code,
                chosen().map(graph -> graph.functions.get(box.name())).orElse(null));
        return definition >= 0 ? definition : box.offset();
    }

    /**
     * Opens the node's own function where the file names one, and the call that adds it
     * otherwise - the function is what a reader clicking a node is looking for.
     *
     * <p>In a notebook, the offsets are into the joined code cells and mean nothing in the file
     * on screen, which is the notebook's JSON. What can be pointed at honestly is the cell, so
     * that is what the caret goes to: the line the cell's source begins on.
     */
    private void goTo(GraphLayout.Box box) {
        TextEditor editor = watching;
        if (editor == null) {
            return;
        }
        int offset = python(box);
        if (offset < 0) {
            return;
        }
        if (notebook != null) {
            Notebook.Cell cell = cellOf(box).orElse(null);
            if (cell == null) {
                return;
            }
            offset = cell.at();
        }
        int[] at = Offsets.lineColumn(editor.text(), offset);
        ide.editors().open(editor.path(), at[0], at[1]);
    }

}
