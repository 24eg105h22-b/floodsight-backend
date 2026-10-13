package com.flooddepth.scene;

import com.flooddepth.mapillary.MapillaryReference;
import java.time.Instant;
import java.util.List;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public final class SceneTestFixtures {
    private SceneTestFixtures() { }
    public static Gemini503Retry retry(Gemini503Retry.Sleeper sleeper) { return new Gemini503Retry(sleeper); }
    public static MapillaryReference reference(String id, int rank) {
        return new MapillaryReference(id, "/api/mapillary/reference/" + id + "/image", Instant.parse("2024-01-01T00:00:00Z"),
                10, 20, rank * 5, "Mapillary", "computed_geometry", "perspective", 45.0, "test-contributor",
                "https://www.mapillary.com/app/?pKey=" + id, 10, 20, 250, 4, 4, false,
                "Distance then ID", "Scene unverified", null, "STRONG_PROXIMITY", "UNVERIFIED_SCENE", rank,
                List.of("Distance"), "ACCESSIBLE_STRUCTURALLY_VALID");
    }
    public static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            image.setRGB(2, 2, 0xFFFFFF);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", bytes);
            return bytes.toByteArray();
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
    }
}
