package com.flooddepth.saferoute;

import com.flooddepth.saferoute.SaferRouteModels.Point;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RouteGeometryTest {
    @Test void middleOfSegmentIsDetectedRatherThanOnlyEndpoints(){assertEquals(11.12,RouteGeometry.routeDistance(new Point(.0001,.01),List.of(new Point(0,0),new Point(0,.02))),.05);}
    @Test void projectionOutsideSegmentUsesNearestEndpoint(){assertEquals(RouteGeometry.distance(new Point(0,-.01),new Point(0,0)),RouteGeometry.segmentDistance(new Point(0,-.01),new Point(0,0),new Point(0,.02)),.001);}
    @Test void coincidentEndpointsAreFinite(){assertEquals(RouteGeometry.distance(new Point(1,1),new Point(0,0)),RouteGeometry.segmentDistance(new Point(1,1),new Point(0,0),new Point(0,0)),.001);}
    @Test void datelineSegmentAndBoundsDoNotCoverTheWholeWorld(){assertEquals(11.12,RouteGeometry.routeDistance(new Point(.0001,180),List.of(new Point(0,179.99),new Point(0,-179.99))),.05);var boxes=RouteGeometry.bounds(List.of(new Point(0,179.99),new Point(0,-179.99)),25);assertEquals(2,boxes.size());assertTrue(boxes.stream().allMatch(b->b.maxLon()-b.minLon()<.03));}
    @Test void distantMiddleReportIsOutsideRoadScaleThreshold(){assertTrue(RouteGeometry.routeDistance(new Point(.001,.01),List.of(new Point(0,0),new Point(0,.02)))>100);}
    @Test void highLatitudeGreatCircleExtremumIsIncludedInPrefilter(){var box=RouteGeometry.bounds(List.of(new Point(70,-10),new Point(70,10)),25).get(0);assertTrue(box.maxLat()>70.2);}
    @Test void standardPolylineEncodingMatchesKnownContract(){assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@",RouteGeometry.encode(List.of(new Point(38.5,-120.2),new Point(40.7,-120.95),new Point(43.252,-126.453))));}
}
