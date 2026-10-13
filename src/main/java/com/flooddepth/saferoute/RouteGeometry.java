package com.flooddepth.saferoute;

import com.flooddepth.saferoute.SaferRouteModels.Point;
import java.util.List;


/** Spherical minor-arc segment distance, including dateline crossings and interior projections. */
public final class RouteGeometry {
    public static final double EARTH=6_371_008.8;
    private RouteGeometry(){ }
    private record V(double x,double y,double z) {
        V add(V b){return new V(x+b.x,y+b.y,z+b.z);} V scale(double k){return new V(x*k,y*k,z*k);}
        double dot(V b){return x*b.x+y*b.y+z*b.z;} V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}
        double length(){return Math.sqrt(dot(this));} V unit(){return scale(1/length());}
    }
    private static V vector(Point p){double lat=Math.toRadians(p.latitude()),lon=Math.toRadians(p.longitude());return new V(Math.cos(lat)*Math.cos(lon),Math.cos(lat)*Math.sin(lon),Math.sin(lat));}
    private static double angle(V a,V b){return Math.atan2(a.cross(b).length(),Math.max(-1,Math.min(1,a.dot(b))));}
    public static double distance(Point a,Point b){return EARTH*angle(vector(a),vector(b));}
    private static boolean onArc(V a,V b,V q,double arc){return angle(a,q)+angle(q,b)<=arc+1e-10;}
    public static double segmentDistance(Point point,Point start,Point end){
        V p=vector(point),a=vector(start),b=vector(end),n=a.cross(b);double arc=angle(a,b);
        double best=Math.min(angle(p,a),angle(p,b));
        if(n.length()<1e-12)return best*EARTH;
        n=n.unit();V projection=p.add(n.scale(-p.dot(n)));
        if(projection.length()>1e-12){V q=projection.unit();if(onArc(a,b,q,arc))best=Math.min(best,angle(p,q));q=q.scale(-1);if(onArc(a,b,q,arc))best=Math.min(best,angle(p,q));}
        return best*EARTH;
    }
    public static double routeDistance(Point p,List<Point> points){
        if(points==null||points.size()<2)throw new IllegalArgumentException("Invalid route geometry");
        double best=Double.POSITIVE_INFINITY;for(int i=1;i<points.size();i++)best=Math.min(best,segmentDistance(p,points.get(i-1),points.get(i)));return best;
    }
    public record Bounds(double minLat,double maxLat,double minLon,double maxLon){ }
    public static List<Bounds> bounds(List<Point> points,double paddingMeters){
        double minLat=90,maxLat=-90,minLon=Double.POSITIVE_INFINITY,maxLon=Double.NEGATIVE_INFINITY;
        double previous=points.get(0).longitude();
        for(Point p:points){minLat=Math.min(minLat,p.latitude());maxLat=Math.max(maxLat,p.latitude());double lon=p.longitude();while(lon-previous>180)lon-=360;while(lon-previous < -180)lon+=360;minLon=Math.min(minLon,lon);maxLon=Math.max(maxLon,lon);previous=lon;}
        // Include latitude extrema of spherical arcs, rather than bounding endpoints alone.
        V north=new V(0,0,1);
        for(int i=1;i<points.size();i++){V a=vector(points.get(i-1)),b=vector(points.get(i)),n=a.cross(b);if(n.length()<1e-12)continue;n=n.unit();V q=north.add(n.scale(-north.dot(n)));if(q.length()<1e-12)continue;q=q.unit();for(V c:List.of(q,q.scale(-1)))if(onArc(a,b,c,angle(a,b))){double lat=Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,c.z))));minLat=Math.min(minLat,lat);maxLat=Math.max(maxLat,lat);}}
        double latPad=Math.toDegrees(paddingMeters/EARTH);minLat=Math.max(-90,minLat-latPad);maxLat=Math.min(90,maxLat+latPad);
        double cosine=Math.cos(Math.toRadians(Math.max(Math.abs(minLat),Math.abs(maxLat))));double lonPad=cosine<1e-8?180:Math.min(180,latPad/cosine);
        minLon-=lonPad;maxLon+=lonPad;
        if(maxLon-minLon>=360)return List.of(new Bounds(minLat,maxLat,-180,180));
        while(minLon < -180){minLon+=360;maxLon+=360;}while(minLon>180){minLon-=360;maxLon-=360;}
        if(maxLon<=180)return List.of(new Bounds(minLat,maxLat,minLon,maxLon));
        return List.of(new Bounds(minLat,maxLat,minLon,180),new Bounds(minLat,maxLat,-180,maxLon-360));
    }
    public static String encode(List<Point> points){StringBuilder out=new StringBuilder();long lat=0,lon=0;for(Point p:points){long y=Math.round(p.latitude()*1e5),x=Math.round(p.longitude()*1e5);delta(out,y-lat);delta(out,x-lon);lat=y;lon=x;}return out.toString();}
    private static void delta(StringBuilder out,long value){long v=value<0?~(value<<1):value<<1;while(v>=32){out.append((char)((32|(v&31))+63));v>>=5;}out.append((char)(v+63));}
}
