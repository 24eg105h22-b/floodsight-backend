package com.flooddepth.scene;

import com.flooddepth.service.ImageStorageService.StoredImageFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

public final class SceneImageValidator {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private SceneImageValidator() { }

    public static byte[] read(StoredImageFile stored) {
        try (var stream = Files.newInputStream(stored.path())) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName();
                    String mime = "PNG".equalsIgnoreCase(format) ? "image/png"
                            : "JPEG".equalsIgnoreCase(format) ? "image/jpeg" : "";
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (!mime.equals(stored.mediaType()) || width < 64 || height < 64
                            || width > 8192 || height > 8192 || (long) width * height > 32_000_000
                            || reader.read(0) == null) throw invalid();
                } finally { reader.dispose(); }
            }
            return bytes;
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Scene comparison requires a decodable JPG or PNG, 64–8192 pixels per side, at most 32 megapixels and 10 MB.");
    }
}
