package com.flooddepth.scene;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** In-memory scene inputs only: never rewrites uploaded files or coordinates. */
@Component
public final class GeminiSceneImage {
    private final int maxDimension;
    public GeminiSceneImage(@Value("${gemini.scene.max-image-dimension:1280}") int maxDimension) {
        if (maxDimension < 64 || maxDimension > 8192) throw new IllegalArgumentException("Gemini scene maximum dimension must be 64–8192 pixels.");
        this.maxDimension = maxDimension;
    }
    public record Transmission(int originalWidth, int originalHeight, int transmittedWidth,
            int transmittedHeight, int originalBytes, int transmittedBytes, String mimeType) { }
    public record Prepared(byte[] bytes, String mimeType, Transmission transmission) { }

    public Prepared prepare(byte[] bytes, String mime) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            BufferedImage original;
            int width, height;
            try {
                reader.setInput(input, true, true);
                String decodedMime = switch (reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> "";
                };
                width = reader.getWidth(0); height = reader.getHeight(0);
                if (!decodedMime.equals(mime) || width < 64 || height < 64 || width > 8192
                        || height > 8192 || (long) width * height > 32_000_000) throw invalid();
                original = reader.read(0);
                if (original == null) throw invalid();
            } finally { reader.dispose(); }
            if (Math.max(width, height) <= maxDimension)
                return new Prepared(bytes, mime, new Transmission(width, height, width, height, bytes.length, bytes.length, mime));
            double scale = (double) maxDimension / Math.max(width, height);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            boolean png = "image/png".equals(mime);
            BufferedImage resized = new BufferedImage(targetWidth, targetHeight,
                    png && original.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (png) {
                if (!ImageIO.write(resized, "png", output)) throw invalid();
            } else {
                var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (var stream = ImageIO.createImageOutputStream(output)) {
                    writer.setOutput(stream);
                    var parameters = writer.getDefaultWriteParam();
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    parameters.setCompressionQuality(.85f);
                    writer.write(null, new javax.imageio.IIOImage(resized, null, null), parameters);
                } finally { writer.dispose(); }
            }
            byte[] transmitted = output.toByteArray();
            return new Prepared(transmitted, mime, new Transmission(width, height, targetWidth, targetHeight,
                    bytes.length, transmitted.length, mime));
        } catch (java.io.IOException | RuntimeException exception) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Gemini scene input requires a valid bounded JPG/PNG image.");
    }
}
