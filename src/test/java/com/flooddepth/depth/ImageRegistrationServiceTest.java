package com.flooddepth.depth;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import static com.flooddepth.depth.RegistrationGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageRegistrationServiceTest {
    final ImageRegistrationService service=new ImageRegistrationService();
    RegistrationGeometry masked(){return new RegistrationGeometry(CalibrationFixtures.regions(),List.of(),List.of());}
    @Test void identicalImagesHaveHighQualityDeterministicPermanentFeatureRegistration(){
        var image=CalibrationFixtures.image();var result=service.register(image,image,masked());
        assertEquals(ImageRegistrationService.Quality.HIGH,result.quality());assertTrue(result.inlierCount()>=30);
        assertEquals(1,result.inlierRatio());assertTrue(result.reprojectionError()<.001);
        var point=new Point(.42,.68);assertTrue(result.transform().map(point).distance(point)<1e-6);
        assertEquals(result,service.register(image,image,masked()));
    }
    @Test void translatedImagesAndCropUseCorrespondenceRatherThanPixelIdentity(){
        var dry=CalibrationFixtures.image();var flood=new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB);var g=flood.createGraphics();g.drawImage(dry,8,6,null);g.dispose();
        var regions=CalibrationFixtures.regions().stream().map(r->new RegistrationRegion(r.regionId(),r.dryBox(),
                new Region(r.floodBox().xMin()+8.0/319,r.floodBox().yMin()+6.0/239,r.floodBox().xMax()+8.0/319,r.floodBox().yMax()+6.0/239),true,.99)).toList();
        var result=service.register(dry,flood,new RegistrationGeometry(regions,List.of(),List.of()));
        assertTrue(result.accepted());assertEquals(8.0/319,result.transform().tx(),.005);assertEquals(6.0/239,result.transform().ty(),.005);
        var crop=dry.getSubimage(10,8,290,220);
        var cropped=CalibrationFixtures.regions().stream().map(r->new RegistrationRegion(r.regionId(),r.dryBox(),
                new Region((r.dryBox().xMin()*319-10)/289,(r.dryBox().yMin()*239-8)/219,
                        Math.min(1,(r.dryBox().xMax()*319-10)/289),Math.min(1,(r.dryBox().yMax()*239-8)/219)),true,.99)).toList();
        var registered=service.register(dry,crop,new RegistrationGeometry(cropped,List.of(),List.of()));
        assertTrue(registered.accepted());assertEquals(319.0/289,registered.transform().a(),.01);
        assertEquals(-10.0/289,registered.transform().tx(),.01);
    }
    @Test void occlusionAndExposureChangeDoNotRequireAllOriginalPixels(){
        var dry=CalibrationFixtures.image();var flood=CalibrationFixtures.image();
        for(int y=0;y<240;y++)for(int x=0;x<320;x++){int c=flood.getRGB(x,y)&255;c=(int)(c*.75+20);flood.setRGB(x,y,new Color(c,c,c).getRGB());}
        var g=flood.createGraphics();g.setColor(Color.WHITE);g.fillRect(20,20,60,65);g.dispose();
        var visual=new RegistrationGeometry(CalibrationFixtures.regions(),List.of(),List.of(new Region(.05,.05,.26,.37)));
        var r=service.register(dry,flood,visual);assertTrue(r.accepted());assertTrue(r.inlierRatio()>.5);
        assertTrue(r.inliers().stream().noneMatch(m->visual.excludedFloodRegions().get(0).contains(m.flood())));
    }
    @Test void croppedTransmittedSizeImagesRemainRegisterableAfterBoundedWorkingImageResizing(){
        var dry=new BufferedImage(1280,960,BufferedImage.TYPE_INT_RGB);var g=dry.createGraphics();g.drawImage(CalibrationFixtures.image(),0,0,1280,960,null);g.dispose();
        var crop=dry.getSubimage(40,32,1160,880);
        var regions=CalibrationFixtures.regions().stream().map(r->new RegistrationRegion(r.regionId(),r.dryBox(),new Region(
                Math.max(0,(r.dryBox().xMin()*1279-40)/1159),Math.max(0,(r.dryBox().yMin()*959-32)/879),
                Math.min(1,(r.dryBox().xMax()*1279-40)/1159),Math.min(1,(r.dryBox().yMax()*959-32)/879)),true,.99)).toList();
        var result=service.register(dry,crop,new RegistrationGeometry(regions,List.of(),List.of()));
        assertTrue(result.accepted(),"quality="+result.quality()+" matches="+result.matchedFeatureCount()+" inliers="+result.inlierCount());
        assertEquals(-40.0/1159,result.transform().tx(),.01);assertEquals(1279.0/1159,result.transform().a(),.01);
    }
    @Test void blankImagesAndNonPermanentRegionsCannotRegister(){
        var blank=new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB);
        assertEquals(ImageRegistrationService.Quality.UNUSABLE,service.register(blank,blank,masked()).quality());
        var regions=CalibrationFixtures.regions().stream().map(r->new RegistrationRegion(r.regionId(),r.dryBox(),r.floodBox(),false,.99)).toList();
        assertFalse(service.register(CalibrationFixtures.image(),CalibrationFixtures.image(),new RegistrationGeometry(regions,List.of(),List.of())).accepted());
    }
    @Test void randomMatchesAndCollinearGeometryCannotAuthorizeMetricTransfer(){
        var random=new Random(92);var pairs=new ArrayList<ImageRegistrationService.Match>();
        for(int i=0;i<60;i++)pairs.add(new ImageRegistrationService.Match(new Point(random.nextDouble(),random.nextDouble()),new Point(random.nextDouble(),random.nextDouble()),"r"+i%3,0));
        assertFalse(service.fit(pairs,320,240).accepted());
        var line=new ArrayList<ImageRegistrationService.Match>();for(int i=1;i<=40;i++)line.add(new ImageRegistrationService.Match(new Point(i/50.0,.5),new Point(i/50.0,.5),"r"+i%3,0));
        assertFalse(service.fit(line,320,240).accepted());
    }
    @Test void ransacRejectsOutliersWhileKeepingDistributedStructureCorrespondences(){
        var random=new Random(18);var pairs=new ArrayList<ImageRegistrationService.Match>();
        for(int i=0;i<50;i++){var p=new Point(.1+random.nextDouble()*.7,.1+random.nextDouble()*.7);pairs.add(new ImageRegistrationService.Match(p,new Point(p.x()+.04,p.y()+.03),"r"+i%3,0));}
        for(int i=0;i<12;i++)pairs.add(new ImageRegistrationService.Match(new Point(random.nextDouble(),random.nextDouble()),new Point(random.nextDouble(),random.nextDouble()),"r"+i%3,0));
        var r=service.fit(pairs,320,240);assertTrue(r.accepted());assertTrue(r.inlierCount()>=50);assertEquals(.04,r.transform().tx(),.002);
        var p=new Point(.43,.62);assertTrue(r.transform().inverse(r.transform().map(p)).distance(p)<1e-6);
    }
}
