package com.roseroots.admin;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class PhotoProcessing {
    static final int BYTE_LIMIT = 8 * 1024 * 1024;
    static final Settings ORIGINAL = new Settings(0, 1, 0, 0, 0, 0, 1);
    static final Settings CARD = new Settings(1.87, 1, 0, 0, 0, 0, 1);

    private PhotoProcessing() {}

    static BufferedImage read(InputStream source) throws IOException {
        byte[] bytes = source.readNBytes(BYTE_LIMIT + 1);
        if (bytes.length > BYTE_LIMIT) {
            throw new IllegalArgumentException("Choose a file no larger than 8 MiB.");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Choose a valid JPEG or PNG photo.");
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new IllegalArgumentException("Choose a JPEG or PNG photo.");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 6000 || height > 6000 || (long) width * height > 16_000_000) {
                    throw new IllegalArgumentException("Choose a photo within 6000 × 6000 pixels and 16 megapixels.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    static BufferedImage render(BufferedImage original, Settings settings, int edge) {
        int turns = Math.floorMod(settings.turns(), 4);
        boolean swap = turns % 2 != 0;
        int rotatedWidth = swap ? original.getHeight() : original.getWidth();
        int rotatedHeight = swap ? original.getWidth() : original.getHeight();
        double aspect = settings.aspect() > 0 ? settings.aspect() : rotatedWidth / (double) rotatedHeight;
        double cropWidth = Math.min(rotatedWidth, rotatedHeight * aspect) / settings.zoom();
        double cropHeight = cropWidth / aspect;
        double x = (rotatedWidth - cropWidth) * (settings.x() + 1) / 2;
        double y = (rotatedHeight - cropHeight) * (settings.y() + 1) / 2;
        double scale = Math.min(1, edge / Math.max(cropWidth, cropHeight));
        int width = Math.max(1, (int) Math.round(cropWidth * scale));
        int height = Math.max(1, (int) Math.round(cropHeight * scale));
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.scale(width / cropWidth, height / cropHeight);
            graphics.translate(rotatedWidth / 2.0 - x, rotatedHeight / 2.0 - y);
            graphics.rotate(turns * Math.PI / 2);
            graphics.translate(-original.getWidth() / 2.0, -original.getHeight() / 2.0);
            graphics.drawImage(original, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        if (settings.brightness() != 0 || settings.contrast() != 1) {
            int[] pixels = output.getRGB(0, 0, width, height, null, 0, width);
            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];
                pixels[index] = channel(pixel >> 16, settings) << 16
                        | channel(pixel >> 8, settings) << 8 | channel(pixel, settings);
            }
            output.setRGB(0, 0, width, height, pixels, 0, width);
        }
        return output;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "JPEG", bytes)) {
            throw new IOException("JPEG encoder unavailable.");
        }
        return bytes.toByteArray();
    }

    private static int channel(int value, Settings settings) {
        return Math.clamp((int) Math.round(((value & 255) - 127.5) * settings.contrast()
                + 127.5 + settings.brightness() * 255), 0, 255);
    }

    record Settings(double aspect, double zoom, double x, double y, int turns,
                    double brightness, double contrast) {}
}
