package com.flooddepth.depth;

import com.flooddepth.scene.GeminiSceneImage;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static com.flooddepth.depth.RegistrationGeometry.*;

/** Optional registered comparison. Reuses registration; never supplies scene or metric evidence. */
public final class VisualPerspectiveGrid {
    public static final String COMPARISON_VERSION="registered-comparison-v1";
    private final ImageRegistrationService registration;
    private final boolean enabled,debug;
    private final Path directory;
    public VisualPerspectiveGrid(ImageRegistrationService registration,boolean enabled,boolean debug,String directory) {
        this.registration=registration;this.enabled=enabled;this.debug=debug;
        Path requested=Path.of(directory);
        if(!requested.isAbsolute()&&requested.startsWith("target")&&Files.isDirectory(Path.of("backend/src/main")))
            requested=Path.of("backend").resolve(requested);
        this.directory=requested.toAbsolutePath().normalize();
    }
    public record Comparison(byte[] bytes,ImageRegistrationService.Quality quality,String reason) {
        public boolean generated(){return bytes!=null&&bytes.length>0;}
        public static Comparison omitted(String reason){return new Comparison(null,ImageRegistrationService.Quality.UNUSABLE,reason);}
    }
    /** Reuses the same bounded registration/search masks. Originals remain untouched; no grid or metric marks. */
    public Comparison prepareComparison(GeminiSceneImage.Prepared flood,GeminiSceneImage.Prepared dry) {
        var quality=ImageRegistrationService.Quality.UNUSABLE;
        if(!enabled)return comparisonResult(null,quality,"DISABLED");
        try {
            var f=ImageIO.read(new ByteArrayInputStream(flood.bytes()));
            var d=ImageIO.read(new ByteArrayInputStream(dry.bytes()));
            if(f==null||d==null)return comparisonResult(null,quality,"DECODE_FAILED");
            var fit=registration.register(d,f,searchWindows());
            if(fit==null)return comparisonResult(null,quality,"REGISTRATION_UNUSABLE");
            quality=fit.quality();
            if(!fit.accepted()||fit.transform()==null)return comparisonResult(null,quality,"REGISTRATION_UNUSABLE");
            if(overlap(fit.transform())<.70)return comparisonResult(null,quality,"INSUFFICIENT_SHARED_VIEW");
            var transform=dryPixelsToFloodPixels(fit.transform(),d.getWidth(),d.getHeight(),f.getWidth(),f.getHeight());
            if(!Double.isFinite(transform.getDeterminant())||transform.getDeterminant()<=0)
                return comparisonResult(null,quality,"INVALID_TRANSFORM");
            var overlay=new BufferedImage(f.getWidth(),f.getHeight(),BufferedImage.TYPE_INT_RGB);
            var g=overlay.createGraphics();
            try {
                g.drawImage(f,0,0,null);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,.45f));
                g.drawImage(d,transform,null);
            }finally{g.dispose();}
            var output=new ByteArrayOutputStream();
            if(!ImageIO.write(overlay,"png",output)||output.size()>10*1024*1024)
                return comparisonResult(null,quality,"OVERLAY_ENCODING_FAILED");
            if(debug)writeComparison(overlay);
            return comparisonResult(output.toByteArray(),quality,"REGISTERED_OVERLAY");
        }catch(IOException|RuntimeException failure){return comparisonResult(null,quality,"LOCAL_COMPARISON_FAILED");}
    }
    private static Comparison comparisonResult(byte[] bytes,ImageRegistrationService.Quality quality,String reason) {
        var result=new Comparison(bytes,quality,reason);
        DepthDiagnostics.logger().info("REGISTERED_COMPARISON requestId={} generated={} registrationQuality={} reason={} metricScale=false",
                org.slf4j.MDC.get("depthRequestId"),result.generated(),quality,reason);
        return result;
    }
    private synchronized void writeComparison(BufferedImage image) {
        try {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,"comparison-",".png");
            try {
                if(!ImageIO.write(image,"png",temp.toFile()))throw new IOException("Unavailable");
                Files.move(temp,directory.resolve("registered-comparison.png"),StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temp);}
            DepthDiagnostics.logger().info("REGISTERED_COMPARISON debugImageSaved=true");
        }catch(IOException|RuntimeException ignored){DepthDiagnostics.logger().warn("REGISTERED_COMPARISON debugImageSaved=false reason=LOCAL_IO_UNAVAILABLE");}
    }
    /** Spatial search windows only. The adapter flags enable the existing feature engine, NOT a semantic
     * confidence claim. These unverified windows are never passed to scene policy or metric estimation. Lower
     * foreground is excluded to reduce traffic/water matches. Gemini must independently check landmarks. */
    static RegistrationGeometry searchWindows() {
        var regions=new ArrayList<RegistrationRegion>();int id=0;
        for(double[] y:new double[][]{{.025,.37},{.22,.65}})
            for(double[] x:new double[][]{{.025,.42},{.30,.70},{.58,.975}}) {
                var box=new Region(x[0],y[0],x[1],y[1]);
                regions.add(new RegistrationRegion("aid-window-"+(++id),box,box,true,1));
            }
        var foreground=List.of(new Region(0,.65,1,1));
        return new RegistrationGeometry(regions,foreground,foreground);
    }
    static AffineTransform dryPixelsToFloodPixels(ImageRegistrationService.Transform t,int dw,int dh,int fw,int fh) {
        return new AffineTransform(t.a()*(fw-1)/(dw-1),t.c()*(fh-1)/(dw-1),
                t.b()*(fw-1)/(dh-1),t.d()*(fh-1)/(dh-1),t.tx()*(fw-1),t.ty()*(fh-1));
    }
    private static double overlap(ImageRegistrationService.Transform t) {
        int valid=0,total=0;
        for(int y=0;y<=8;y++)for(int x=0;x<=8;x++) {
            total++;
            try{t.inverse(new Point(x/8.,y/8.));valid++;}catch(IllegalArgumentException ignored){ }
        }
        return (double)valid/total;
    }
}
