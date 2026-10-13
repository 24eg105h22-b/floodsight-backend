package com.flooddepth.depth;

import org.springframework.stereotype.Service;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.util.*;
import static com.flooddepth.depth.RegistrationGeometry.*;

/** Bounded pure-Java Harris/normalized-patch matching + deterministic affine RANSAC.
 * Affine correspondence is intentionally conservative: significant parallax/large viewpoint changes
 * fail rather than being interpreted as a metric homography of a non-planar road scene. */
@Service
public class ImageRegistrationService {
    public enum Quality { HIGH, MEDIUM, LOW, UNUSABLE }
    public record Match(Point dry,Point flood,String regionId,double errorPixels) { }
    public record Transform(double a,double b,double tx,double c,double d,double ty) {
        public Point map(Point p) { return checked(a*p.x()+b*p.y()+tx,c*p.x()+d*p.y()+ty); }
        public Point inverse(Point p) {
            double det=a*d-b*c;if(Math.abs(det)<1e-8)throw new IllegalArgumentException("Singular registration");
            double x=p.x()-tx,y=p.y()-ty;return checked((d*x-b*y)/det,(-c*x+a*y)/det);
        }
        private static Point checked(double x,double y) {
            if(x<0||x>1||y<0||y>1)throw new IllegalArgumentException("Outside registered image");return new Point(x,y);
        }
        double error(Match m,int w,int h) {
            return Math.hypot((a*m.dry.x()+b*m.dry.y()+tx-m.flood.x())*w,
                    (c*m.dry.x()+d*m.dry.y()+ty-m.flood.y())*h);
        }
    }
    public record Registration(int matchedFeatureCount,int inlierCount,double inlierRatio,double reprojectionError,
            String transformationType,Quality quality,Transform transform,List<Match> inliers,int floodWidth,int floodHeight) {
        public boolean accepted() { return quality==Quality.HIGH||quality==Quality.MEDIUM; }
    }
    private record Feature(Point point,double[] descriptor,double strength) { }
    private record Gray(int width,int height,double[][] pixels) { }
    public Registration register(BufferedImage dry,BufferedImage flood,RegistrationGeometry observations) {
        if(dry==null||flood==null||observations==null)return unusable();
        var dg=gray(dry);var fg=gray(flood);var matches=new ArrayList<Match>();
        var usedDry=new HashSet<Point>();var usedFlood=new HashSet<Point>();
        for(var region:observations.registrationRegions()) {
            if(!region.permanentStructure()||region.confidence()<.90)continue;
            var a=features(dg,region.dryBox(),observations,true);
            var b=features(fg,region.floodBox(),observations,false);
            for(int i=0;i<a.size();i++) {
                int j=nearest(a.get(i),b,true);if(j<0||nearest(b.get(j),a,false)!=i)continue;
                if(usedDry.add(a.get(i).point)&&usedFlood.add(b.get(j).point))
                    matches.add(new Match(a.get(i).point,b.get(j).point,region.regionId(),0));
            }
        }
        return fit(matches,flood.getWidth(),flood.getHeight());
    }
    public Registration fit(List<Match> pairs,int width,int height) {
        if(pairs==null||pairs.size()<8||pairs.size()>960||width<32||height<32)return unusable();
        double threshold=Math.max(2,Math.max(width,height)/213.0);
        List<Match> best=List.of();Transform bestModel=null;double bestError=Double.POSITIVE_INFINITY;
        var random=new Random(0x464C4F4F44L);
        for(int iteration=0;iteration<600;iteration++) {
            int x=random.nextInt(pairs.size()),y=random.nextInt(pairs.size()),z=random.nextInt(pairs.size());
            if(x==y||x==z||y==z)continue;
            Transform candidate=solve(List.of(pairs.get(x),pairs.get(y),pairs.get(z)));if(!plausible(candidate))continue;
            var in=pairs.stream().filter(m->candidate.error(m,width,height)<=threshold).toList();
            double error=in.stream().mapToDouble(m->candidate.error(m,width,height)).sum();
            if(in.size()>best.size()||(in.size()==best.size()&&error<bestError)) { best=in;bestModel=candidate;bestError=error; }
        }
        if(bestModel==null||best.size()<8)return unusable(pairs.size());
        for(int refit=0;refit<2;refit++) {
            var candidate=solve(best);if(!plausible(candidate))return unusable(pairs.size());
            bestModel=candidate;best=pairs.stream().filter(m->candidate.error(m,width,height)<=threshold).toList();
        }
        final Transform model=bestModel;
        var in=best.stream().map(m->new Match(m.dry,m.flood,m.regionId,model.error(m,width,height))).toList();
        double rms=Math.sqrt(in.stream().mapToDouble(m->m.errorPixels*m.errorPixels).average().orElse(Double.POSITIVE_INFINITY));
        double ratio=(double)in.size()/pairs.size();long regions=in.stream().map(Match::regionId).distinct().count();
        double sx=span(in,true),sy=span(in,false);
        double normalizedError=rms*640/Math.max(width,height);
        Quality quality=in.size()>=30&&ratio>=.65&&normalizedError<=2&&regions>=3&&sx>=.25&&sy>=.20 ? Quality.HIGH
                : in.size()>=16&&ratio>=.50&&normalizedError<=3&&regions>=2&&sx>=.20&&sy>=.15 ? Quality.MEDIUM
                : in.size()>=8&&ratio>=.30&&normalizedError<=5 ? Quality.LOW:Quality.UNUSABLE;
        return new Registration(pairs.size(),in.size(),ratio,rms,"AFFINE_2D",quality,model,List.copyOf(in),width,height);
    }
    private static double span(List<Match> in,boolean x) {
        return in.stream().mapToDouble(m->x?m.dry.x():m.dry.y()).max().orElse(0)
                -in.stream().mapToDouble(m->x?m.dry.x():m.dry.y()).min().orElse(0);
    }
    private static boolean plausible(Transform t) {
        if(t==null)return false;double det=t.a*t.d-t.b*t.c;
        return Double.isFinite(det)&&det>.15&&det<5&&Math.abs(t.a)<3&&Math.abs(t.d)<3
                &&Math.abs(t.b)<.8&&Math.abs(t.c)<.8&&Math.abs(t.tx)<.6&&Math.abs(t.ty)<.6;
    }
    private static Transform solve(List<Match> pairs) {
        double[][] matrix=new double[3][3];double[] x=new double[3],y=new double[3];
        for(var p:pairs) {
            double[] row={p.dry.x(),p.dry.y(),1};
            for(int i=0;i<3;i++) {x[i]+=row[i]*p.flood.x();y[i]+=row[i]*p.flood.y();
                for(int j=0;j<3;j++)matrix[i][j]+=row[i]*row[j];}
        }
        var ax=linear(matrix,x);var ay=linear(matrix,y);return ax==null||ay==null?null:new Transform(ax[0],ax[1],ax[2],ay[0],ay[1],ay[2]);
    }
    private static double[] linear(double[][] m,double[] b) {
        double[][] a=new double[3][4];for(int i=0;i<3;i++){System.arraycopy(m[i],0,a[i],0,3);a[i][3]=b[i];}
        for(int col=0;col<3;col++) {
            int pivot=col;for(int r=col+1;r<3;r++)if(Math.abs(a[r][col])>Math.abs(a[pivot][col]))pivot=r;
            if(Math.abs(a[pivot][col])<1e-10)return null;
            var swap=a[col];a[col]=a[pivot];a[pivot]=swap;double divisor=a[col][col];
            for(int j=col;j<4;j++)a[col][j]/=divisor;
            for(int r=0;r<3;r++)if(r!=col){double factor=a[r][col];for(int j=col;j<4;j++)a[r][j]-=factor*a[col][j];}
        }
        return new double[]{a[0][3],a[1][3],a[2][3]};
    }
    private static int nearest(Feature a,List<Feature> list,boolean ratioTest) {
        double first=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;int index=-1;
        for(int i=0;i<list.size();i++) {
            double distance=0;for(int j=0;j<a.descriptor.length;j++){double diff=a.descriptor[j]-list.get(i).descriptor[j];distance+=diff*diff;}
            distance/=a.descriptor.length;
            if(distance<first){second=first;first=distance;index=i;}else if(distance<second)second=distance;
        }
        return first>.45||(ratioTest&&(!Double.isFinite(second)||first>=.64*second))?-1:index;
    }
    private static List<Feature> features(Gray image,Region box,RegistrationGeometry evidence,boolean dry) {
        var candidates=new ArrayList<Feature>();var pixels=image.pixels;
        for(int y=10;y<image.height-10;y+=2)for(int x=10;x<image.width-10;x+=2) {
            var point=new Point((double)x/(image.width-1),(double)y/(image.height-1));
            if(!box.contains(point)||excluded(point,evidence,dry))continue;
            // The whole descriptor footprint must lie in the permanent mask, not merely its centre.
            var lo=new Point((double)(x-8)/(image.width-1),(double)(y-8)/(image.height-1));
            var hi=new Point((double)(x+8)/(image.width-1),(double)(y+8)/(image.height-1));
            if(!box.contains(lo)||!box.contains(hi)||excludedFootprint(new Region(lo.x(),lo.y(),hi.x(),hi.y()),evidence,dry))continue;
            double xx=0,xy=0,yy=0;
            for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
                double gx=pixels[y+j][x+i+1]-pixels[y+j][x+i-1],gy=pixels[y+j+1][x+i]-pixels[y+j-1][x+i];
                xx+=gx*gx;xy+=gx*gy;yy+=gy*gy;
            }
            double response=xx*yy-xy*xy-.04*(xx+yy)*(xx+yy);if(response<.0005)continue;
            double[] descriptor=new double[81];double mean=0;int k=0;
            for(int j=-8;j<=8;j+=2)for(int i=-8;i<=8;i+=2){descriptor[k++]=pixels[y+j][x+i];mean+=pixels[y+j][x+i];}
            mean/=81;double norm=0;for(double v:descriptor)norm+=(v-mean)*(v-mean);norm=Math.sqrt(norm/81);
            if(norm<.025)continue;for(int i=0;i<81;i++)descriptor[i]=(descriptor[i]-mean)/norm;
            candidates.add(new Feature(point,descriptor,response));
        }
        candidates.sort(Comparator.comparingDouble(Feature::strength).reversed().thenComparingDouble(f->f.point.y()).thenComparingDouble(f->f.point.x()));
        var selected=new ArrayList<Feature>();
        for(var f:candidates) {
            if(selected.stream().anyMatch(s->Math.hypot((f.point.x()-s.point.x())*image.width,(f.point.y()-s.point.y())*image.height)<8))continue;
            selected.add(f);if(selected.size()==120)break;
        }
        return selected;
    }
    private static boolean excluded(Point p,RegistrationGeometry e,boolean dry) {
        // All calibration anchors are excluded from alignment, including vehicles removed in the flood view.
        return (dry?e.excludedDryRegions():e.excludedFloodRegions()).stream().anyMatch(r->r.contains(p));
    }
    private static boolean excludedFootprint(Region patch,RegistrationGeometry e,boolean dry) {
        var excluded=new ArrayList<>(dry?e.excludedDryRegions():e.excludedFloodRegions());
        return excluded.stream().anyMatch(r->patch.xMin()<r.xMax()&&patch.xMax()>r.xMin()&&patch.yMin()<r.yMax()&&patch.yMax()>r.yMin());
    }
    private static Gray gray(BufferedImage source) {
        double scale=Math.min(1,640.0/Math.max(source.getWidth(),source.getHeight()));
        int w=(int)Math.round(source.getWidth()*scale),h=(int)Math.round(source.getHeight()*scale);
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,w,h,null);}finally{g.dispose();}
        var pixels=new double[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=image.getRGB(x,y);pixels[y][x]=(.299*((rgb>>16)&255)+.587*((rgb>>8)&255)+.114*(rgb&255))/255;}
        return new Gray(w,h,pixels);
    }
    public static Registration unusable(){return unusable(0);}
    private static Registration unusable(int count){return new Registration(count,0,0,Double.POSITIVE_INFINITY,"NONE",Quality.UNUSABLE,null,List.of(),0,0);}
}
