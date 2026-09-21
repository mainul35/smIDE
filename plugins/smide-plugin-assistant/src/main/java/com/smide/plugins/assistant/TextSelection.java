package com.smide.plugins.assistant;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Selecting words out of something that is drawn rather than typed.
 *
 * <p>An answer in the assistant is not a text box: it is headings, paragraphs, lists and coloured
 * code, each drawn as its own node, and none of them selectable. Which is fine until the one thing
 * anybody wants is the three lines in the middle - a command, a path, the name of a class - and
 * the only way to get them is to copy the whole conversation and cut it up somewhere else.
 *
 * <p>So the selection is put back by hand. Every piece of text that was drawn is kept in the order
 * it was drawn in; a press finds the piece under the pointer and the character within it, a drag
 * finds the other end, and everything between the two is selected whole. The highlight is drawn
 * from the shapes the text itself reports for a range, laid on a pane behind it, which is how a
 * text field draws its own: the toolkit knows where the characters are, and this asks it.
 */
final class TextSelection {

    private final Pane highlight;
    private final Parent content;
    /** Every drawn piece of text, in the order it appears, rebuilt whenever the pane is. */
    private final List<Text> pieces = new ArrayList<>();

    private Text anchorPiece;
    private int anchorIndex;
    private Text focusPiece;
    private int focusIndex;

    TextSelection(Pane highlight, Parent content) {
        this.highlight = highlight;
        this.content = content;
        highlight.setMouseTransparent(true);
        highlight.getStyleClass().add("md-selection");

        content.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.isPrimaryButtonDown()) {
                content.requestFocus();
                begin(e);
            }
        });
        content.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (e.isPrimaryButtonDown() && anchorPiece != null) {
                extend(e);
                e.consume();
            }
        });
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.C && (e.isControlDown() || e.isMetaDown())) {
                copy();
                e.consume();
            } else if (e.getCode() == KeyCode.A && (e.isControlDown() || e.isMetaDown())) {
                selectAll();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                clear();
            }
        });
    }

    /** Called as the pane is rebuilt: what is on screen now, in reading order. */
    void rebuilt(List<Text> drawn) {
        pieces.clear();
        pieces.addAll(drawn);
        clear();
    }

    /** What is selected, or an empty string. */
    String selected() {
        int from = pieces.indexOf(anchorPiece);
        int to = pieces.indexOf(focusPiece);
        if (from < 0 || to < 0) {
            return "";
        }
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        int startIndex = from <= to ? anchorIndex : focusIndex;
        int endIndex = from <= to ? focusIndex : anchorIndex;
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String text = pieces.get(i).getText();
            int a = i == start ? Math.min(startIndex, text.length()) : 0;
            int b = i == end ? Math.min(endIndex, text.length()) : text.length();
            out.append(text, Math.min(a, b), Math.max(a, b));
            if (i != end) {
                out.append(pieces.get(i).getParent() == pieces.get(i + 1).getParent() ? "" : "\n");
            }
        }
        return out.toString();
    }

    void copy() {
        String text = selected();
        if (text.isBlank()) {
            return;
        }
        ClipboardContent clip = new ClipboardContent();
        clip.putString(text);
        Clipboard.getSystemClipboard().setContent(clip);
    }

    void selectAll() {
        if (pieces.isEmpty()) {
            return;
        }
        anchorPiece = pieces.get(0);
        anchorIndex = 0;
        focusPiece = pieces.get(pieces.size() - 1);
        focusIndex = focusPiece.getText().length();
        paint();
    }

    void clear() {
        anchorPiece = null;
        focusPiece = null;
        highlight.getChildren().clear();
    }

    private void begin(MouseEvent e) {
        clear();
        Text piece = pieceAt(e);
        if (piece == null) {
            return;
        }
        anchorPiece = piece;
        anchorIndex = indexIn(piece, e);
        focusPiece = piece;
        focusIndex = anchorIndex;
    }

    private void extend(MouseEvent e) {
        Text piece = pieceAt(e);
        if (piece == null) {
            return;
        }
        focusPiece = piece;
        focusIndex = indexIn(piece, e);
        paint();
    }

    /**
     * The piece of text under the pointer.
     *
     * <p>Under it exactly when the pointer is on a word, and otherwise the nearest piece on the
     * same line, so dragging through the space at the end of a line keeps selecting rather than
     * stopping dead.
     */
    private Text pieceAt(MouseEvent e) {
        javafx.geometry.Point2D point = new javafx.geometry.Point2D(e.getSceneX(), e.getSceneY());
        Text nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Text piece : pieces) {
            javafx.geometry.Bounds bounds = piece.localToScene(piece.getBoundsInLocal());
            if (bounds == null) {
                continue;
            }
            if (bounds.contains(point)) {
                return piece;
            }
            double dy = point.getY() < bounds.getMinY() ? bounds.getMinY() - point.getY()
                    : point.getY() > bounds.getMaxY() ? point.getY() - bounds.getMaxY() : 0;
            double dx = point.getX() < bounds.getMinX() ? bounds.getMinX() - point.getX()
                    : point.getX() > bounds.getMaxX() ? point.getX() - bounds.getMaxX() : 0;
            double distance = dy * 1000 + dx;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = piece;
            }
        }
        return nearest;
    }

    private static int indexIn(Text piece, MouseEvent e) {
        javafx.geometry.Point2D local = piece.sceneToLocal(e.getSceneX(), e.getSceneY());
        return piece.hitTest(local).getInsertionIndex();
    }

    /** Draws the highlight from the shapes the text reports for the selected range. */
    private void paint() {
        highlight.getChildren().clear();
        int from = pieces.indexOf(anchorPiece);
        int to = pieces.indexOf(focusPiece);
        if (from < 0 || to < 0) {
            return;
        }
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        int startIndex = from <= to ? anchorIndex : focusIndex;
        int endIndex = from <= to ? focusIndex : anchorIndex;
        for (int i = start; i <= end; i++) {
            Text piece = pieces.get(i);
            String text = piece.getText();
            int a = i == start ? Math.min(startIndex, text.length()) : 0;
            int b = i == end ? Math.min(endIndex, text.length()) : text.length();
            if (a == b) {
                continue;
            }
            PathElement[] shape = piece.rangeShape(Math.min(a, b), Math.max(a, b));
            if (shape == null || shape.length == 0) {
                continue;
            }
            Path path = new Path(shape);
            path.getStyleClass().add("md-selection-band");
            path.setMouseTransparent(true);
            // The shape is in the text's own space; the highlight layer is somewhere else.
            javafx.geometry.Bounds inScene = piece.localToScene(piece.getBoundsInLocal());
            javafx.geometry.Bounds here = highlight.sceneToLocal(inScene);
            path.setLayoutX(here.getMinX() - piece.getBoundsInLocal().getMinX());
            path.setLayoutY(here.getMinY() - piece.getBoundsInLocal().getMinY());
            highlight.getChildren().add(path);
        }
    }

    /** Every piece of text under a node, in the order it is drawn. */
    static List<Text> textsUnder(Node node) {
        List<Text> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<Text> out) {
        if (node instanceof Text text) {
            out.add(text);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
