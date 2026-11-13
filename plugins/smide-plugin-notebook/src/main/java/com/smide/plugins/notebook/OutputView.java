package com.smide.plugins.notebook;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

/**
 * What a cell produced, drawn under it.
 *
 * <p>Four things are drawn, which is what the task of reading a notebook actually needs:
 * text a cell printed, the value of its last expression, a PNG it drew, and the traceback
 * of whatever went wrong. Everything else a kernel can send - HTML, LaTeX, SVG, a widget -
 * is stored faithfully and shown as its {@code text/plain} alternative, which every such
 * output carries precisely so that a reader without a browser in it has something to see.
 * That is honest and it is useful; a half-working HTML renderer would be neither.
 *
 * <p>Text is drawn in the editor's own monospaced font, because a cell's output is aligned
 * by spaces more often than not - a dataframe, a table, a progress bar - and a proportional
 * font turns all of it into rubble.
 */
final class OutputView extends VBox {

    /** Beyond this many characters one output is cut, with a line saying so. */
    private static final int LIMIT = 200_000;

    private final String fontFamily;
    private final int fontSize;

    OutputView(String fontFamily, int fontSize) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        getStyleClass().add("notebook-outputs");
        setSpacing(2);
        setPadding(new Insets(4, 8, 6, 8));
        setVisible(false);
        setManaged(false);
    }

    /** Replaces everything drawn with this list; an empty list hides the view entirely. */
    void show(List<Output> outputs) {
        getChildren().clear();
        if (outputs == null || outputs.isEmpty()) {
            setVisible(false);
            setManaged(false);
            return;
        }
        setVisible(true);
        setManaged(true);
        for (Output output : outputs) {
            output.png().ifPresent(png -> picture(png).ifPresent(getChildren()::add));
            String text = Ansi.strip(output.text());
            if (!text.isEmpty()) {
                getChildren().add(text(text, output));
            }
        }
        if (getChildren().isEmpty()) {
            // Everything this output held was something nothing here draws, and it had no
            // text alternative either. Saying so beats a blank space.
            getChildren().add(text("[" + outputs.size() + " output(s) this editor cannot draw;"
                    + " they are kept in the file]", null));
        }
    }

    private Label text(String text, Output output) {
        String shown = text.length() > LIMIT
                ? text.substring(0, LIMIT) + "\n[... " + (text.length() - LIMIT)
                + " more characters, kept in the file]"
                : text;
        Label label = new Label(shown);
        label.setWrapText(true);
        label.setFont(Font.font(fontFamily, fontSize));
        label.getStyleClass().add("notebook-output-text");
        if (output != null && Output.ERROR.equals(output.outputType())) {
            label.getStyleClass().add("notebook-output-error");
        } else if (output != null && output.isStandardError()) {
            label.getStyleClass().add("notebook-output-stderr");
        }
        return label;
    }

    /**
     * A PNG, at its own size but never wider than the cell.
     *
     * <p>Base64 that will not decode, or bytes that are not an image, give nothing rather
     * than an exception: the output came from somewhere else and may be truncated, and a
     * notebook with one bad picture in it must still open.
     */
    private java.util.Optional<ImageView> picture(String base64) {
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(base64);
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError() || image.getWidth() <= 0) {
                return java.util.Optional.empty();
            }
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            // Shrink to the cell when it is too wide, but never blow a small plot up.
            view.fitWidthProperty().bind(widthProperty().subtract(24)
                    .map(width -> Math.min(width.doubleValue(), image.getWidth())));
            return java.util.Optional.of(view);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }
}
