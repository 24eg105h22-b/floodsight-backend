package com.flooddepth.depth;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import static com.flooddepth.depth.RegistrationGeometry.*;

public final class CalibrationFixtures {
    private CalibrationFixtures(){ }
    public static BufferedImage image() {
        var image=new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        var random=new Random(793);
        for(int y=0;y<240;y+=4)for(int x=0;x<320;x+=4){int c=40+random.nextInt(190);g.setColor(new Color(c,c,c));g.fillRect(x,y,4,4);}
        g.setColor(Color.DARK_GRAY);g.drawRect(25,25,265,180);g.dispose();return image;
    }
    public static byte[] bytes(BufferedImage image){try{var b=new ByteArrayOutputStream();ImageIO.write(image,"png",b);return b.toByteArray();}catch(Exception e){throw new AssertionError(e);}}
    public static List<RegistrationRegion> regions(){return List.of(
            new RegistrationRegion("left",new Region(.05,.05,.45,.50),new Region(.05,.05,.45,.50),true,.99),
            new RegistrationRegion("right",new Region(.55,.05,.95,.50),new Region(.55,.05,.95,.50),true,.99),
            new RegistrationRegion("wall",new Region(.45,.45,.85,.85),new Region(.45,.45,.85,.85),true,.99));}
    public static ImageRegistrationService.Registration identityRegistration(){
        return new ImageRegistrationService.Registration(60,60,1,0,"AFFINE_2D",ImageRegistrationService.Quality.HIGH,
                new ImageRegistrationService.Transform(1,0,0,0,1,0),List.of(),320,240);
    }
}
