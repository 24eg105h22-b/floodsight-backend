package com.flooddepth.scene;

import com.flooddepth.service.ImageStorageService.StoredImageFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SceneImageValidatorTest {
    @TempDir Path dir;
    @Test
    void acceptsRealDecodablePngBytes() throws Exception {
        Path path = dir.resolve("photo.png"); Files.write(path, SceneTestFixtures.png());
        assertArrayEquals(SceneTestFixtures.png(), SceneImageValidator.read(new StoredImageFile(path, "image/png")));
    }
    @Test
    void rejectsEmptyOversizedNonImageAndMimeMismatchInputs() throws Exception {
        Path path = dir.resolve("photo.png");
        for (byte[] bytes : new byte[][]{new byte[0], new byte[]{1, 2, 3}, "%PDF-1.0 fake PDF".getBytes(), new byte[10 * 1024 * 1024 + 1]}) {
            Files.write(path, bytes);
            assertThrows(IllegalArgumentException.class, () -> SceneImageValidator.read(new StoredImageFile(path, "image/png")));
        }
        Files.write(path, SceneTestFixtures.png());
        assertThrows(IllegalArgumentException.class, () -> SceneImageValidator.read(new StoredImageFile(path, "image/jpeg")));
    }
}
