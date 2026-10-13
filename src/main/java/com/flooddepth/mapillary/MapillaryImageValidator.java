package com.flooddepth.mapillary;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Narrow structural/blank-frame check, never evidence of a matching scene. */
final class MapillaryImageValidator {
    static void validate(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Unreadable image content.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) {
                    throw invalid("Unsupported encoded image format.");
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // Check dimensions BEFORE decoding to bound allocation for untrusted CDN data.
                if (width < 64 || height < 64 || width > 4096 || height > 4096
                        || (long) width * height > 8_000_000) throw invalid("Invalid thumbnail dimensions.");
                BufferedImage image = reader.read(0);
                if (image == null) throw invalid("Unreadable image content.");
                int[] min = {255, 255, 255}, max = {0, 0, 0};
                for (int y = 0; y < height; y += Math.max(1, height / 64)) {
                    for (int x = 0; x < width; x += Math.max(1, width / 64)) {
                        int rgb = image.getRGB(x, y);
                        for (int c = 0; c < 3; c++) {
                            int value = (rgb >> (c * 8)) & 255;
                            min[c] = Math.min(min[c], value);
                            max[c] = Math.max(max[c], value);
                        }
                    }
                }
                if (max[0] - min[0] <= 2 && max[1] - min[1] <= 2 && max[2] - min[2] <= 2) {
                    throw invalid("Nearly constant frame; no inspectable visual detail.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof MapillaryException safe) throw safe;
            throw invalid("Unreadable image content.");
        }
    }

    private static MapillaryException invalid(String message) {
        return new MapillaryException(org.springframework.http.HttpStatus.BAD_GATEWAY, message);
    }
}
