package com.flooddepth.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GeminiSceneImageTest {
    @TempDir Path directory;
    @Test void downscalePreservesAspectRatioMimeAndOriginalFile() throws Exception {
        byte[] original = image(4608,2592,"jpeg",false);
        Path file = directory.resolve("original.jpg"); Files.write(file,original);
        byte[] before = original.clone();
        var result = new GeminiSceneImage(1280).prepare(Files.readAllBytes(file),"image/jpeg");
        var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(1280, decoded.getWidth()); assertEquals(720, decoded.getHeight());
        assertEquals("image/jpeg",result.mimeType()); assertArrayEquals(before,Files.readAllBytes(file));
        assertArrayEquals(before, original); assertEquals(original.length,result.transmission().originalBytes());
        assertEquals(result.bytes().length,result.transmission().transmittedBytes());
    }
    @Test void noResizeAtOrBelowThresholdKeepsOriginalBytes() throws Exception {
        for (int width : new int[]{320,1280}) {
            byte[] original=image(width,320,"png",true);
            var result=new GeminiSceneImage(1280).prepare(original,"image/png");
            assertSame(original,result.bytes()); assertEquals(width,result.transmission().transmittedWidth());
        }
    }
    @Test void portraitPngRetainsAlphaAndNeverCrops() throws Exception {
        var result=new GeminiSceneImage(1280).prepare(image(800,1600,"png",true),"image/png");
        var decoded=ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(640,decoded.getWidth()); assertEquals(1280,decoded.getHeight());
        assertTrue(decoded.getColorModel().hasAlpha()); assertEquals("image/png",result.mimeType());
    }
    @Test void customThresholdIsHonored() throws Exception {
        var result=new GeminiSceneImage(640).prepare(image(1600,800,"jpeg",false),"image/jpeg");
        assertEquals(640,result.transmission().transmittedWidth()); assertEquals(320,result.transmission().transmittedHeight());
    }
    @Test void invalidDecodeMimeDimensionsAndConfigurationRejected() throws Exception {
        var preprocessing=new GeminiSceneImage(1280);
        assertThrows(IllegalArgumentException.class,()->preprocessing.prepare(new byte[]{1,2},"image/png"));
        assertThrows(IllegalArgumentException.class,()->preprocessing.prepare(image(128,128,"png",false),"image/jpeg"));
        assertThrows(IllegalArgumentException.class,()->preprocessing.prepare(image(9000,64,"png",false),"image/png"));
        assertThrows(IllegalArgumentException.class,()->new GeminiSceneImage(0));
        assertThrows(IllegalArgumentException.class,()->new GeminiSceneImage(9000));
    }
    @Test void smallerDiagnosticPreprocessingReducesPayloadButDoesNotChangeProductionDefault() throws Exception {
        var patterned=new BufferedImage(2304,1296,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<patterned.getHeight();y++)for(int x=0;x<patterned.getWidth();x++)
            patterned.setRGB(x,y,((x/7%2)*180<<16)|((y/9%2)*160<<8)|((x+y)/11%2)*120);
        var output=new ByteArrayOutputStream();ImageIO.write(patterned,"png",output);var original=output.toByteArray();
        var before=original.clone();var regular=new GeminiSceneImage(1280).prepare(original,"image/png");
        var smaller=new GeminiSceneImage(1024).prepare(original,"image/png");
        assertEquals(1280,regular.transmission().transmittedWidth());assertEquals(720,regular.transmission().transmittedHeight());
        assertEquals(1024,smaller.transmission().transmittedWidth());assertEquals(576,smaller.transmission().transmittedHeight());
        assertTrue(smaller.bytes().length<regular.bytes().length);assertArrayEquals(before,original);
        System.out.println("LOCAL_PREPROCESSING_COMPARISON bytes1280="+regular.bytes().length+" bytes1024="+smaller.bytes().length);
        // This is a payload/decoding check only; it cannot establish provider latency or scene accuracy.
    }
    static byte[] image(int width,int height,String format,boolean alpha) throws Exception {
        var image=new BufferedImage(width,height,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream(); assertTrue(ImageIO.write(image,format,bytes)); return bytes.toByteArray();
    }
}
