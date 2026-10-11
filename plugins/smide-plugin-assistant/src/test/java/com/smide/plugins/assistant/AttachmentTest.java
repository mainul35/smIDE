package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting a screenshot into the shape an endpoint will take it in.
 *
 * <p>What goes to the model is base64 inside a {@code data:image/png;base64,} URL, so whatever
 * was picked has to come out of here as a PNG - and small enough that sending it is not the slow
 * part of asking a question.
 */
class AttachmentTest {

    @TempDir
    Path folder;

    private Path picture(String name, int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        canvas.setColor(Color.BLUE);
        canvas.fillRect(0, 0, width, height);
        canvas.setColor(Color.WHITE);
        canvas.fillRect(width / 4, height / 4, width / 2, height / 2);
        canvas.dispose();
        Path file = folder.resolve(name);
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    @Test
    void aScreenshotComesOutAsPngBase64() throws IOException {
        Attachment attached = Attachment.of(picture("shot.png", 800, 600, "png"));

        assertTrue(attached.isReady(), attached.problem());
        assertEquals(800, attached.width());
        assertEquals(600, attached.height());
        byte[] decoded = Base64.getDecoder().decode(attached.base64());
        // The first bytes of every PNG, which is what the data: URL promises it is sending.
        assertEquals((byte) 0x89, decoded[0]);
        assertEquals("PNG", new String(decoded, 1, 3, StandardCharsets.US_ASCII));
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(decoded)),
                "and it reads back as a picture");
    }

    @Test
    void somethingThatIsNotAPngIsOneByTheTimeItIsSent() throws IOException {
        Attachment attached = Attachment.of(picture("photo.jpg", 400, 300, "jpg"));

        assertTrue(attached.isReady(), attached.problem());
        byte[] decoded = Base64.getDecoder().decode(attached.base64());
        assertEquals("PNG", new String(decoded, 1, 3, StandardCharsets.US_ASCII),
                "the url says image/png, so this had better be one");
    }

    @Test
    void somethingFromAPhoneIsCutDownToWhatAScreenNeeds() throws IOException {
        Attachment attached = Attachment.of(picture("huge.png", 4000, 2250, "png"));

        assertTrue(attached.isReady(), attached.problem());
        assertEquals(1600, attached.width(), "the long side");
        assertEquals(900, attached.height(), "and the shape it had");
        assertTrue(attached.bytes() < 1_000_000,
                "a megabyte of base64 per question is the slow part: " + attached.bytes());
    }

    @Test
    void aSmallOneIsLeftTheSizeItIs() throws IOException {
        Attachment attached = Attachment.of(picture("small.png", 320, 200, "png"));

        assertEquals(320, attached.width());
        assertEquals(200, attached.height());
    }

    @Test
    void whatIsNotAPictureSaysSoRatherThanBreaking() throws IOException {
        Path text = folder.resolve("notes.txt");
        Files.writeString(text, "this is not a picture");

        Attachment attached = Attachment.of(text);

        assertFalse(attached.isReady());
        assertNotNull(attached.problem());
        assertTrue(attached.describe().contains("notes.txt"), attached.describe());
    }

    @Test
    void andNeitherIsAFileThatIsNotThere() {
        Attachment attached = Attachment.of(folder.resolve("gone.png"));

        assertFalse(attached.isReady());
        assertTrue(attached.problem().contains("no such file"), attached.problem());
    }
}
