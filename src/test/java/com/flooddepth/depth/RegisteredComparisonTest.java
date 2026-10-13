package com.flooddepth.depth;

import com.flooddepth.scene.GeminiSceneImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegisteredComparisonTest {
    @TempDir Path dir;
    final GeminiSceneImage preprocessing=new GeminiSceneImage(1280);
    GeminiSceneImage.Prepared solid(int w,int h,int rgb) {
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,rgb);
        return preprocessing.prepare(CalibrationFixtures.bytes(image),"image/png");
    }
    ImageRegistrationService.Registration fit(ImageRegistrationService.Quality quality,ImageRegistrationService.Transform t) {
        return new ImageRegistrationService.Registration(60,60,1,0,"AFFINE_2D",quality,t,List.of(),320,240);
    }
    @ParameterizedTest @EnumSource(value=ImageRegistrationService.Quality.class,names={"HIGH","MEDIUM"})
    void acceptedRegistrationBlendsOnlyOriginalPixelsWithNoScaleLabelsOrWaterline(ImageRegistrationService.Quality quality) throws Exception {
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(fit(quality,new ImageRegistrationService.Transform(1,0,0,0,1,0)));
        var dry=solid(160,120,0xFF0000);var flood=solid(320,240,0x0000FF);
        byte[] drySnapshot=dry.bytes().clone(),floodSnapshot=flood.bytes().clone();
        var result=new VisualPerspectiveGrid(registration,true,true,dir.toString()).prepareComparison(flood,dry);
        assertTrue(result.generated());assertEquals(quality,result.quality());
        var image=ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(320,image.getWidth());assertEquals(240,image.getHeight());
        // Uniform originals produce a uniform blend: no text, grid, metric marks or invented waterline.
        int expected=image.getRGB(100,100);
        assertEquals(115,(expected>>16)&255,1);assertEquals(140,expected&255,1);
        for(int y=0;y<238;y++)for(int x=0;x<318;x++)assertEquals(expected,image.getRGB(x,y));
        assertArrayEquals(drySnapshot,dry.bytes());assertArrayEquals(floodSnapshot,flood.bytes());
        assertArrayEquals(result.bytes(),Files.readAllBytes(dir.resolve("registered-comparison.png")));
        verify(registration,times(1)).register(any(),any(),any());
    }
    @Test void dryToFloodTransformProjectsDryContentWithoutFillingUncoveredAreasWithInventedPixels() throws Exception {
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(fit(ImageRegistrationService.Quality.HIGH,
                new ImageRegistrationService.Transform(1,0,.1,0,1,0)));
        var flood=solid(320,240,0x0000FF);var dry=solid(160,120,0xFF0000);
        var result=new VisualPerspectiveGrid(registration,true,false,dir.toString()).prepareComparison(flood,dry);
        assertTrue(result.generated());var image=ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(0x0000FF,image.getRGB(10,100)&0xFFFFFF);
        assertEquals(115,(image.getRGB(60,100)>>16)&255,1);
        assertFalse(Files.exists(dir.resolve("registered-comparison.png")));
    }
    @ParameterizedTest @EnumSource(value=ImageRegistrationService.Quality.class,names={"LOW","UNUSABLE"})
    void badQualityNeverProducesAnOverlay(ImageRegistrationService.Quality quality) {
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(fit(quality,new ImageRegistrationService.Transform(1,0,0,0,1,0)));
        var result=new VisualPerspectiveGrid(registration,true,true,dir.toString()).prepareComparison(solid(320,240,0),solid(320,240,0));
        assertFalse(result.generated());assertEquals(quality,result.quality());assertNull(result.bytes());
    }
    @Test void poorOverlapAndMissingTransformAreOmittedEvenWithHighReportedQuality() {
        var registration=mock(ImageRegistrationService.class);var input=solid(320,240,0);
        for(var t:new ImageRegistrationService.Transform[]{null,new ImageRegistrationService.Transform(1,0,.5,0,1,0)}) {
            when(registration.register(any(),any(),any())).thenReturn(fit(ImageRegistrationService.Quality.HIGH,t));
            assertFalse(new VisualPerspectiveGrid(registration,true,true,dir.toString()).prepareComparison(input,input).generated());
        }
    }
    @Test void disabledRegistrationDecodeOrLocalFailureNeverThrowsIntoDepthWorkflow() {
        var registration=mock(ImageRegistrationService.class);var input=solid(320,240,0);
        assertFalse(new VisualPerspectiveGrid(registration,false,true,dir.toString()).prepareComparison(input,input).generated());
        verifyNoInteractions(registration);
        when(registration.register(any(),any(),any())).thenThrow(new IllegalArgumentException("fixture"));
        assertFalse(new VisualPerspectiveGrid(registration,true,true,dir.toString()).prepareComparison(input,input).generated());
        var invalid=new GeminiSceneImage.Prepared(new byte[]{1},"image/png",input.transmission());
        assertFalse(new VisualPerspectiveGrid(registration,true,true,dir.toString()).prepareComparison(invalid,input).generated());
    }
    @Test void developmentWriteFailureDoesNotDisableTheUsableOverlay() throws Exception {
        var file=dir.resolve("file");Files.writeString(file,"fixture");
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(CalibrationFixtures.identityRegistration());
        var input=solid(320,240,0);
        assertTrue(new VisualPerspectiveGrid(registration,true,true,file.toString()).prepareComparison(input,input).generated());
    }
    @Test void realRegistrationIsReusedWithExistingConservativeSearchMasksAndNoMetricEvidence() {
        var input=preprocessing.prepare(CalibrationFixtures.bytes(CalibrationFixtures.image()),"image/png");
        var result=new VisualPerspectiveGrid(new ImageRegistrationService(),true,false,dir.toString()).prepareComparison(input,input);
        assertTrue(result.generated(),result.reason());
        assertFalse(VisualPerspectiveGrid.searchWindows().excludedFloodRegions().isEmpty());
    }
    @Test void safeDiagnosticsReportGenerationAndRegistrationQualityWithoutPayload() {
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(CalibrationFixtures.identityRegistration());
        var input=solid(320,240,0);
        try(var log=new DepthDiagnosticCapture()) {
            new VisualPerspectiveGrid(registration,true,false,dir.toString()).prepareComparison(input,input);
            assertTrue(log.messages().contains("generated=true registrationQuality=HIGH"));
            assertFalse(log.messages().contains(java.util.Base64.getEncoder().encodeToString(input.bytes())));
        }
    }
}
