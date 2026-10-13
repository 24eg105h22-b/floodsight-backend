package com.flooddepth.saferoute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class GeoapifyClientTest {
    final RestClient.Builder builder=RestClient.builder();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    static final String TEST_KEY="mock-routing-credential";
    final GeoapifyClient client=new GeoapifyClient(builder.build(),TEST_KEY);
    final Point start=new Point(10,20);
    final Destination end=new Destination("Test destination",10,20.01);
    static String route(double middleLatitude) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("type","FeatureCollection","properties",Map.of("apiKey",TEST_KEY),"features",List.of(Map.of(
            "type","Feature","properties",Map.of("distance",1500,"time",180),"geometry",Map.of("type","MultiLineString","coordinates",List.of(List.of(List.of(20,10),List.of(20.005,middleLatitude),List.of(20.01,10))))))));
    }
    @Test void destinationUsesDocumentedBiasOrderAndRetainsOnlyCleanFields() {
        server.expect(request->{
            var query=org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            assertEquals("https://api.geoapify.com/v1/geocode/search",request.getURI().getScheme()+"://"+request.getURI().getHost()+request.getURI().getPath());
            assertEquals("proximity:20.0,10.0",java.net.URLDecoder.decode(query.getFirst("bias"),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("json",query.getFirst("format"));assertEquals("1",query.getFirst("limit"));
        }).andRespond(withSuccess("{\"results\":[{\"lat\":10,\"lon\":20.01,\"formatted\":\"Test destination\",\"apiKey\":\""+TEST_KEY+"\"}]}",MediaType.APPLICATION_JSON));
        assertEquals(end,client.geocode("Test destination",start));server.verify();assertFalse(end.toString().contains(TEST_KEY));
    }
    @Test void noDestinationHasFixedUserMessageAndNoCause() {
        server.expect(r->{}).andRespond(withSuccess("{\"results\":[]}",MediaType.APPLICATION_JSON));
        var error=assertThrows(RoutePlanningException.class,()->client.geocode("Unknown",start));assertEquals(RoutePlanningException.Code.DESTINATION_NOT_FOUND,error.code());assertNull(error.getCause());
    }
    @Test void threeActualDistinctProfilesAreParsedWithoutReturningProviderProperties() throws Exception {
        for(var pair:List.of(Map.entry("balanced",10.0),Map.entry("short",10.002),Map.entry("less_maneuvers",9.998)))
            server.expect(request->{assertTrue(request.getURI().getRawQuery().contains("type="+pair.getKey()));assertTrue(request.getURI().getRawQuery().contains("mode=drive"));assertFalse(request.getURI().getRawQuery().contains("alternatives="));})
                .andRespond(withSuccess(route(pair.getValue()),MediaType.APPLICATION_JSON));
        var result=client.routes(start,end);assertEquals(3,result.size());assertEquals(10,result.get(0).geometry().get(0).latitude());assertEquals(20,result.get(0).geometry().get(0).longitude());
        assertEquals(1500,result.get(0).distanceMeters());assertEquals(180,result.get(0).durationSeconds());assertFalse(result.toString().contains(TEST_KEY));server.verify();
    }
    @Test void identicalProviderPathsAreNotFabricatedIntoAlternatives() throws Exception {
        for(int i=0;i<3;i++)server.expect(r->{}).andRespond(withSuccess(route(10),MediaType.APPLICATION_JSON));
        assertEquals(1,client.routes(start,end).size());server.verify();
    }
    @Test void emptyRoutesAndProviderFailuresAreSafe() {
        for(int i=0;i<3;i++)server.expect(r->{}).andRespond(withSuccess("{\"features\":[]}",MediaType.APPLICATION_JSON));
        assertEquals(RoutePlanningException.Code.NO_ROUTES,assertThrows(RoutePlanningException.class,()->client.routes(start,end)).code());server.verify();
    }
    @Test void unavailableProviderAndMissingKeyNeverExposeCredentialsOrCause() {
        server.expect(r->{}).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(TEST_KEY+" PRIVATE_RESPONSE"));
        var error=assertThrows(RoutePlanningException.class,()->client.geocode("place",start));assertEquals(RoutePlanningException.Code.ROUTING_UNAVAILABLE,error.code());assertNull(error.getCause());assertFalse(error.toString().contains(TEST_KEY));
        var missing=new GeoapifyClient(builder.build(),"");assertEquals(RoutePlanningException.Code.ROUTING_UNAVAILABLE,assertThrows(RoutePlanningException.class,()->missing.geocode("place",start)).code());server.verify();
    }
    @Test void malformedDisconnectedAndSwappedOutOfRangeGeometryAreRejected() throws Exception {
        var mapper=new ObjectMapper();
        for(String geometry:List.of("{\"type\":\"MultiLineString\",\"coordinates\":[[[20,10],[20.001,10]],[[21,11],[21.001,11]]]}",
            "{\"type\":\"LineString\",\"coordinates\":[[20,100],[21,100]]}","{\"type\":\"Polygon\",\"coordinates\":[]}","{\"type\":\"LineString\",\"coordinates\":[[\"20\",10],[21,10]]}"))
            assertThrows(IllegalArgumentException.class,()->GeoapifyClient.parseGeometry(mapper.readTree(geometry)));
        var joined=GeoapifyClient.parseGeometry(mapper.readTree("{\"type\":\"MultiLineString\",\"coordinates\":[[[20,10],[20.001,10]],[[20.001,10],[20.002,10]]]}"));assertEquals(3,joined.size());
    }
    @Test void excessiveEndpointSnappingIsNotAcceptedAsTheRequestedJourney() throws Exception {
        server.expect(r->{}).andRespond(withSuccess(route(10),MediaType.APPLICATION_JSON));
        var error=assertThrows(RoutePlanningException.class,()->client.routes(new Point(10.01,20),end));
        assertEquals(RoutePlanningException.Code.ROUTING_UNAVAILABLE,error.code());server.verify();
    }
    @Test void secretBearingTransportDoesNotFollowRedirectsToOtherEndpoints() throws Exception {
        try(var source=new okhttp3.mockwebserver.MockWebServer();var target=new okhttp3.mockwebserver.MockWebServer()){
            source.start();target.start();
            source.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location",target.url("/redirected?apiKey="+TEST_KEY)));
            target.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("private"));
            var request=GeoapifyClient.requestFactory(1000).createRequest(source.url("/source?apiKey="+TEST_KEY).uri(),HttpMethod.GET);
            try(var response=request.execute()){assertEquals(302,response.getStatusCode().value());}
            assertEquals(0,target.getRequestCount());assertEquals(1,source.getRequestCount());
        }
    }
    @Test void timeoutIsSanitized() {
        server.expect(r->{}).andRespond(withException(new java.net.SocketTimeoutException(TEST_KEY)));
        var error=assertThrows(RoutePlanningException.class,()->client.geocode("place",start));assertNull(error.getCause());assertFalse(error.getMessage().contains(TEST_KEY));
    }
    @Test void hyderabadGeoJsonIsConvertedOnceAndEncodedInLatitudeLongitudeOrder() throws Exception {
        var geometry=GeoapifyClient.parseGeometry(new ObjectMapper().readTree("{\"type\":\"LineString\",\"coordinates\":[[78.49515,17.37701],[78.482,17.371],[78.47466,17.36156]]}"));
        assertEquals(new Point(17.37701,78.49515),geometry.get(0));
        assertTrue(geometry.stream().allMatch(p->p.latitude()>17.3&&p.latitude()<17.5&&p.longitude()>78.4&&p.longitude()<78.6));
        assertEquals("i}`iBuab~Mpd@dqA~y@zl@",RouteGeometry.encode(geometry));
    }
}
