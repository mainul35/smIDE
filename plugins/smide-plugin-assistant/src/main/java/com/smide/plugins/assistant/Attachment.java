package com.smide.plugins.assistant;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * A picture on its way to the model.
 *
 * <p>A screenshot is the shortest way to say what is wrong with a window: the stack trace in it,
 * the button that is in the wrong place, the dialog nobody can read. The model is sent it the way
 * every OpenAI-compatible endpoint expects one - base64 inside a {@code data:} URL - and what
 * goes in that URL says {@code image/png}, so what is sent had better be a PNG whatever was
 * picked.
 *
 * <p>Which is the other reason everything is re-encoded here rather than passed along: a phone
 * photograph is twelve megapixels and eight megabytes, base64 makes it eleven, and a model that
 * accepts it at all will take a minute thinking about pixels nobody needed. Scaled to something a
 * screen-sized picture needs, it is a few hundred kilobytes and reads exactly as well.
 */
record Attachment(String name, String base64, int width, int height, int bytes, String problem) {

    /** Beyond this, the long side is scaled down: enough to read a screenshot, no more. */
    private static final int LONGEST_SIDE = 1600;
    /** A file bigger than this is not a screenshot and is not worth reading into memory. */
    private static final long MOST_BYTES = 25L * 1024 * 1024;

    static Attachment failed(String name, String problem) {
        return new Attachment(name, null, 0, 0, 0, problem);
    }

    boolean isReady() {
        return problem == null && base64 != null && !base64.isBlank();
    }

    /** What the chip under the question box says. */
    String describe() {
        return isReady()
                ? name + "  " + width + "x" + height + ", " + (bytes / 1024) + " KB"
                : name + " - " + problem;
    }

    /**
     * Reads a picture and makes it ready to send. Blocks: callers are off the window's thread.
     */
    static Attachment of(Path file) {
        String name = file.getFileName() == null ? "image" : file.getFileName().toString();
        try {
            if (!Files.isRegularFile(file)) {
                return failed(name, "there is no such file");
            }
            if (Files.size(file) > MOST_BYTES) {
                return failed(name, "that file is " + (Files.size(file) / (1024 * 1024))
                        + " MB; pictures to send are smaller than " + (MOST_BYTES / (1024 * 1024)));
            }
            BufferedImage read = ImageIO.read(file.toFile());
            if (read == null) {
                return failed(name, "that is not a picture this IDE can read");
            }
            BufferedImage image = scaled(read);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                return failed(name, "that picture could not be turned into a PNG");
            }
            byte[] png = out.toByteArray();
            return new Attachment(name, Base64.getEncoder().encodeToString(png),
                    image.getWidth(), image.getHeight(), png.length, null);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return failed(name, "it could not be read: " + e);
        }
    }

    /**
     * The same picture, no bigger than a screen needs, and in a form PNG can hold.
     *
     * <p>Always redrawn rather than only when it is too large: a picture read from a JPEG or a GIF
     * comes back in whatever colour model that format used, and some of those write out as a PNG
     * nothing else will open.
     */
    private static BufferedImage scaled(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        double factor = longest > LONGEST_SIDE ? (double) LONGEST_SIDE / longest : 1.0;
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas = out.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            canvas.drawImage(source, 0, 0, width, height, null);
        } finally {
            canvas.dispose();
        }
        return out;
    }
}
