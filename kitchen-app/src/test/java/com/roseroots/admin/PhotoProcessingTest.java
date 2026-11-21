package com.roseroots.admin;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhotoProcessingTest {
    @Test
    void cropsAtTheSelectedPositionAndZoomProducesSmallerCrop() {
        var original = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 40, 40);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(40, 0, 40, 40);
        graphics.dispose();
        var left = PhotoProcessing.render(original, new PhotoProcessing.Settings(1, 1, -1, 0, 0, 0, 1), 500);
        var right = PhotoProcessing.render(original, new PhotoProcessing.Settings(1, 2, 1, 0, 0, 0, 1), 500);
        assertEquals(40, left.getWidth());
        assertEquals(20, right.getWidth());
        assertEquals(Color.RED.getRGB(), left.getRGB(10, 10));
        assertEquals(Color.BLUE.getRGB(), right.getRGB(10, 10));
    }

    @Test
    void rotatesActualPixelsAndAppliesBrightnessAndContrast() {
        var original = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        original.setRGB(0, 0, new Color(80, 80, 80).getRGB());
        var rotated = PhotoProcessing.render(original,
                new PhotoProcessing.Settings(0, 1, 0, 0, 1, .20, 1.5), 500);
        assertEquals(20, rotated.getWidth());
        assertEquals(30, rotated.getHeight());
        assertEquals(107, rotated.getRGB(19, 0) & 255);
        assertEquals(80, original.getRGB(0, 0) & 255);
    }

    @Test
    void encodesARealJpegAndRejectsNonImages() throws Exception {
        var original = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = PhotoProcessing.jpeg(original);
        assertEquals(40, PhotoProcessing.read(new ByteArrayInputStream(jpeg)).getWidth());
        assertThrows(IllegalArgumentException.class, () -> PhotoProcessing.read(new ByteArrayInputStream(new byte[]{5})));
    }
}
