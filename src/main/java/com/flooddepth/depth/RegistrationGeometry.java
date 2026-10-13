package com.flooddepth.depth;

import java.util.List;

/** Normalized registration search windows and occlusion masks; never physical measurements. */
public record RegistrationGeometry(List<RegistrationRegion> registrationRegions,
        List<Region> excludedDryRegions, List<Region> excludedFloodRegions) {
    public RegistrationGeometry {
        registrationRegions=List.copyOf(registrationRegions);
        excludedDryRegions=List.copyOf(excludedDryRegions); excludedFloodRegions=List.copyOf(excludedFloodRegions);
    }
    public record Point(double x,double y) {
        public Point { if(!unit(x)||!unit(y))throw new IllegalArgumentException("Invalid normalized point"); }
        public double distance(Point other) { return Math.hypot(x-other.x,y-other.y); }
    }
    public record Region(double xMin,double yMin,double xMax,double yMax) {
        public Region { if(!unit(xMin)||!unit(yMin)||!unit(xMax)||!unit(yMax)||xMax<=xMin||yMax<=yMin)
            throw new IllegalArgumentException("Invalid normalized region"); }
        public boolean contains(Point p) { return p.x>=xMin&&p.x<=xMax&&p.y>=yMin&&p.y<=yMax; }
    }
    public record RegistrationRegion(String regionId,Region dryBox,Region floodBox,
            boolean permanentStructure,double confidence) { }
    static boolean unit(double n) { return Double.isFinite(n)&&n>=0&&n<=1; }
}
