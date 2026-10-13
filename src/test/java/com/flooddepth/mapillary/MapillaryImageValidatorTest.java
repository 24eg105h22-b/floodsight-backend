package com.flooddepth.mapillary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MapillaryImageValidatorTest {
    @Test
    void acceptsDecodableNonconstantImageWithoutClaimingSceneQuality() {
        assertDoesNotThrow(() -> MapillaryImageValidator.validate(MapillaryServiceTest.validImage()));
    }

    @Test
    void rejectsBrokenImageContent() {
        assertThrows(MapillaryException.class, () -> MapillaryImageValidator.validate(new byte[]{1, 2, 3}));
    }

    @ParameterizedTest
    @CsvSource({"32,64", "64,32", "4097,64", "3000,3000"})
    void rejectsBadDimensionsBeforeDecoding(int width, int height) throws Exception {
        assertThrows(MapillaryException.class, () -> MapillaryImageValidator.validate(encoded(width, height)));
    }

    @Test
    void rejectsCompletelySolidRedFrame() throws Exception {
        assertThrows(MapillaryException.class, () -> MapillaryImageValidator.validate(encoded(64, 64)));
    }

    private byte[] encoded(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        return bytes.toByteArray();
    }
}
