package com.flooddepth.mapillary;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MapillaryDiscoveryTest {
    private record Fixture(MapillaryService service, MockRestServiceServer server) { }
    private Fixture fixture() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new MapillaryService(builder.build(), "mock-only", 1000, 100), server);
    }
    private static String image(int id, double lat, int heading, String sequence) {
        return "{\"id\":\"" + id + "\",\"captured_at\":1704067200000,"
                + "\"thumb_1024_url\":\"https://scontent.test.fbcdn.net/ref.jpg\","
                + "\"computed_geometry\":{\"type\":\"Point\",\"coordinates\":[20," + lat + "]},"
                + "\"computed_compass_angle\":" + heading + ",\"sequence\":"
                + (sequence == null ? "null" : "\"" + sequence + "\"") + "}";
    }
    private static String data(String... images) { return "{\"data\":[" + String.join(",", images) + "]}"; }
    private static void respond(Fixture f, String body) {
        f.server.expect(r -> {
            assertEquals("graph.mapillary.com", r.getURI().getHost());
            assertEquals("OAuth mock-only", r.getHeaders().getFirst("Authorization"));
            assertFalse(r.getURI().toString().contains("mock-only"));
        }).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test void metadataPoolIsTwentyWithoutDownloadingAnyImages() {
        var f = fixture();
        respond(f, data(java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(i -> image(i, 10 + i * .00001, i % 8 * 45, null)).toArray(String[]::new)));
        var r = f.service.discoverReferences(10,20);
        assertEquals(20, r.candidates().size()); assertEquals(40, r.metadataValidCandidates());
        assertEquals(0,r.imagesScreened());
        assertTrue(r.candidates().stream().allMatch(c -> c.sceneState().equals("UNVERIFIED_SCENE") && c.imageValidation().equals("NOT_SCREENED")));
        f.server.verify();
    }

    @Test void headingCoveragePrecedesRedundantNearbyFramesAndIsOrderIndependent() {
        String a = image(1,10.00001,150,"sequence"), b = image(2,10.00002,155,"sequence"), c = image(3,10.0005,300,"sequence");
        var f = fixture(); var g = fixture();
        for (var entry : List.of(f,g)) { respond(entry, entry == f ? data(a,b,c) : data(c,b,a)); respond(entry,data()); }
        var first=f.service.discoverReferences(10,20); var second=g.service.discoverReferences(10,20);
        assertEquals(List.of("1","3","2"),first.candidates().stream().map(MapillaryReference::imageId).toList());
        assertEquals(first.candidates(),second.candidates()); assertEquals(first.datasetFingerprint(),second.datasetFingerprint());
        f.server.verify(); g.server.verify();
    }

    @Test void followsOnlyBoundedSafeCursorsOnTheServerConstructedGraphEndpoint() {
        var f=fixture();
        respond(f,"{\"data\":["+image(1,10,0,null)+"],\"paging\":{\"next\":\"https://evil.test/secret\",\"cursors\":{\"after\":\"cursor_1\"}}}");
        f.server.expect(r -> { assertEquals("graph.mapillary.com",r.getURI().getHost()); assertTrue(r.getURI().getQuery().contains("after=cursor_1")); })
                .andRespond(withSuccess(data(image(2,10.0001,180,null)),MediaType.APPLICATION_JSON));
        assertEquals(2,f.service.discoverReferences(10,20).candidates().size()); f.server.verify();
    }

    @Test void repeatedOrInvalidCursorNeverLoopsOrFollowsNextUrl() {
        for (String cursor : List.of("https://evil.test", "valid")) {
            var f=fixture();
            String body="{\"data\":["+image(1,10,0,null)+"],\"paging\":{\"next\":\"https://evil.test\",\"cursors\":{\"after\":\""+cursor+"\"}}}";
            respond(f,body); if(cursor.equals("valid"))respond(f,body);
            assertTrue(f.service.discoverReferences(10,20).searchTruncated()); f.server.verify();
        }
    }

    @Test void spatialPagingHasThreePageLimitAndDeduplicates() {
        var f=fixture();
        for(int i=1;i<=3;i++)respond(f,"{\"data\":["+image(1,10,0,null)+"],\"paging\":{\"next\":\"ignored\",\"cursors\":{\"after\":\"page"+i+"\"}}}");
        var r=f.service.discoverReferences(10,20); assertEquals(3,r.candidatesExamined());
        assertEquals(1,r.candidates().size()); assertEquals(2,r.duplicatesRemoved()); assertTrue(r.searchTruncated()); f.server.verify();
    }

    @Test void discoversAdjacentFrameButRejectsOtherSequenceAndOutsideRadius() {
        var f=fixture();
        respond(f,data(image(2,10.00001,0,"seq")));
        respond(f,"{\"data\":[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]}");
        respond(f,image(1,10.0005,180,"seq")); respond(f,image(3,10.01,180,"seq"));
        var r=f.service.discoverReferences(10,20);
        assertEquals(List.of("2","1"),r.candidates().stream().map(MapillaryReference::imageId).toList());
        assertEquals(3,r.candidatesExamined()); f.server.verify();
        var g=fixture(); respond(g,data(image(2,10,0,"seq"))); respond(g,"{\"data\":[{\"id\":\"2\"},{\"id\":\"3\"}]}");
        respond(g,image(3,10,180,"other")); assertEquals(1,g.service.discoverReferences(10,20).candidates().size()); g.server.verify();
    }

    @Test void optionalSequenceFailureKeepsSpatialResultsButRateLimitFails() {
        var f=fixture(); respond(f,data(image(1,10,0,"seq")));
        f.server.expect(r -> {}).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        var r=f.service.discoverReferences(10,20); assertEquals(1,r.candidates().size()); assertTrue(r.searchTruncated()); f.server.verify();
        var g=fixture(); respond(g,data(image(1,10,0,"seq")));
        g.server.expect(q -> {}).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,assertThrows(MapillaryException.class,()->g.service.discoverReferences(10,20)).getStatus());
    }

    @Test void noSpatialResultsCannotInventReferenceOrSequence() {
        var f=fixture(); respond(f,data()); assertTrue(f.service.discoverReferences(10,20).candidates().isEmpty()); f.server.verify();
    }

    @Test void diagnosticExactMetadataReportsOutsideRadiusWithoutMakingItSelectable() {
        var f=fixture(); respond(f,image(99,10.01,123,"seq"));
        var r=f.service.referenceMetadata("99",10,20);
        assertTrue(r.distanceMeters()>250); assertEquals(10.01,r.latitude());
        assertEquals("seq",r.sequenceId()); assertEquals(123,r.compassAngle());
        assertEquals("UNVERIFIED_SCENE",r.sceneState()); assertEquals("NOT_SCREENED",r.imageValidation());
        assertEquals(0,r.rank()); f.server.verify();
    }

    @Test void exactMetadataRejectsMalformedAndMismatchedIdsWithoutReturningProviderUrls() {
        var f=fixture(); assertThrows(IllegalArgumentException.class,()->f.service.referenceMetadata("../99",10,20));
        respond(f,image(98,10,0,null)); assertThrows(MapillaryException.class,()->f.service.referenceMetadata("99",10,20));
        f.server.verify();
        var g=fixture(); respond(g,image(99,10,0,null));
        var r=g.service.referenceMetadata("99",10,20);
        assertEquals("/api/mapillary/reference/99/image",r.imageUrl()); assertFalse(r.toString().contains("fbcdn")); g.server.verify();
    }

    @Test void auditRetainsRanksBeyondNormalTwentyWithoutChangingNormalLimit() {
        String body=data(java.util.stream.IntStream.rangeClosed(1,40)
                .mapToObj(i->image(i,10+i*.00001,0,null)).toArray(String[]::new));
        var f=fixture(); respond(f,body); var audit=f.service.auditDiscovery(10,20);
        var g=fixture(); respond(g,body); var normal=g.service.discoverReferences(10,20);
        assertEquals(40,audit.candidates().size()); assertEquals(20,normal.candidates().size());
        assertEquals(normal.candidates(),audit.candidates().subList(0,20));
        assertEquals(40,audit.candidates().get(39).rank()); f.server.verify(); g.server.verify();
    }

    @Test void diverseCoverageCannotStarveNearSequenceFramesFromTheMetadataPool() {
        var images = new java.util.ArrayList<String>();
        for(int i=1;i<=20;i++) images.add(image(i,10+i*.00001,300,null));
        for(int i=21;i<=40;i++) images.add(image(i,10+.0006+(i-21)*.00005,(i%8)*45,null));
        var f=fixture(); respond(f,data(images.toArray(String[]::new)));
        var r=f.service.discoverReferences(10,20);
        var nearby=r.candidates().stream().filter(c->c.imageId().equals("7")).findFirst().orElseThrow();
        assertTrue(nearby.rank()<=12, "At most five diverse seeds may precede the seventh nearest frame");
        assertEquals(20,r.candidates().size());
        assertTrue(r.candidates().stream().filter(c->Integer.parseInt(c.imageId())<=15).count()>=15);
        f.server.verify();
    }

    @Test void broadDiscoveryQueriesInnerAreaBeforeOuterPoolWithoutHardcodedLocation() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var f=new Fixture(new MapillaryService(builder.build(),"mock-only",1000,250),server);
        respond(f,data(image(1,10,0,null))); respond(f,data(image(2,10.001,180,null)));
        var r=f.service.discoverReferences(10,20);
        assertEquals("1",r.candidates().get(0).imageId()); assertEquals(2,r.candidatesExamined()); f.server.verify();
    }

    @Test void closeNeighborsMustAgreeOnSequenceTimePositionAndCircularHeading() {
        var f=fixture(); respond(f,image(2,10,359,"seq"));
        respond(f,"{\"data\":[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]}");
        respond(f,image(1,10.00005,1,"seq"));
        f.server.expect(r->{}).andRespond(withSuccess(MapillaryServiceTest.validImage(),MediaType.IMAGE_PNG));
        respond(f,image(3,10.0003,1,"seq"));
        var anchor=f.service.referenceMetadata("2",10,20);
        assertEquals(List.of("1"),f.service.closeSequenceNeighbors(anchor,10,20).stream().map(MapillaryReference::imageId).toList());
        f.server.verify();
        for(String bad:List.of(image(3,10,40,"seq"),image(3,10,1,"other"),
                image(3,10,1,"seq").replace("1704067200000","1704067206000"))) {
            var g=fixture(); respond(g,image(2,10,359,"seq"));
            respond(g,"{\"data\":[{\"id\":\"2\"},{\"id\":\"3\"}]}"); respond(g,bad);
            var a=g.service.referenceMetadata("2",10,20);
            assertTrue(g.service.closeSequenceNeighbors(a,10,20).isEmpty()); g.server.verify();
        }
    }
}
