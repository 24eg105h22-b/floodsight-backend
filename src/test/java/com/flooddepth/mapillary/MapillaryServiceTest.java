package com.flooddepth.mapillary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MapillaryServiceTest {
    // Synthetic coordinates for mocked tests; these are not Domalguda coordinates.
    private static final double LAT = 10;
    private static final double LON = 20;
    private static final long OLDER = 1_577_836_800_000L;
    private static final long NEWER = 1_704_067_200_000L;
    private static final String TOKEN = "mock-mapillary-token";

    @Test
    void parsesMetadataAndRanksConservativelyByExactDistanceRatherThanResponseOrder() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {
            assertEquals("graph.mapillary.com", request.getURI().getHost());
            assertEquals("/images", request.getURI().getPath());
            assertEquals("OAuth " + TOKEN, request.getHeaders().getFirst("Authorization"));
            assertFalse(request.getURI().toString().contains(TOKEN));
            assertTrue(request.getURI().getQuery().contains("bbox="));
            assertTrue(request.getURI().getQuery().contains("limit=100"));
        }).andRespond(withSuccess(search(
                image("1", 10.0006, OLDER), // ~67 m away, first result must not win
                image("2", 10.00005, OLDER),
                image("3", 10.0001, NEWER)), MediaType.APPLICATION_JSON));
        accessibleImages(fixture, 3);

        MapillaryReference result = fixture.service.findReference(LAT, LON);
        assertEquals("2", result.imageId());
        assertEquals("/api/mapillary/reference/2/image", result.imageUrl());
        assertEquals(Instant.ofEpochMilli(OLDER), result.capturedAt());
        assertEquals("Mapillary", result.source());
        assertEquals("computed_geometry", result.geometrySource());
        assertEquals("perspective", result.cameraType());
        assertEquals(45.0, result.compassAngle());
        assertEquals("test-contributor", result.creator());
        assertEquals(10.00005, result.latitude());
        assertEquals(LON, result.longitude());
        assertEquals(5.56, result.distanceMeters(), 0.1);
        assertEquals(3, result.suitableCandidates());
        assertFalse(result.searchTruncated());
        assertTrue(result.suitabilityNote().contains("unverified"));
        fixture.server.verify();
    }

    @Test
    void distanceBandTakesPriorityOverNewerFartherCapture() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, search(image("1", 10.0006, NEWER), image("2", 10.00001, OLDER)));
        accessibleImages(fixture, 2);
        assertEquals("2", fixture.service.findReference(LAT, LON).imageId());
    }

    @Test
    void skipsIncompleteCandidateAndFallsBackToValidOriginalGeometry() {
        Fixture fixture = fixture(TOKEN);
        String fallback = image("7", LAT, NEWER).replace("computed_geometry", "geometry");
        respond(fixture, search("{\"id\":\"8\"}", fallback));
        accessibleImages(fixture, 1);
        MapillaryReference reference = fixture.service.findReference(LAT, LON);
        assertEquals("7", reference.imageId());
        assertEquals("geometry", reference.geometrySource());
        assertEquals(2, reference.candidatesExamined());
        assertEquals(1, reference.suitableCandidates());
    }

    @Test
    void ignoresProviderNextUrlAndReportsBoundedSearch() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, "{\"data\":[" + image("1", LAT, NEWER)
                + "],\"paging\":{\"next\":\"https://untrusted.test/?access_token=secret\"}}");
        accessibleImages(fixture, 1);
        assertTrue(fixture.service.findReference(LAT, LON).searchTruncated());
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"data\":[]}",
            "{\"data\":[{\"id\":\"1\",\"captured_at\":1704067200000,"
                    + "\"thumb_1024_url\":\"https://scontent.test.fbcdn.net/ref.jpg\","
                    + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[20,10.01]}}]}"})
    void handlesEmptySearchAndImagesOutsideCircularRadius(String body) {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, body);
        assertStatus(HttpStatus.NOT_FOUND, () -> fixture.service.findReference(LAT, LON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "{\"data\":{}}", "{\"data\":[null]}",
            "{\"data\":[{\"id\":\"1\"}]}", "{\"error\":{\"message\":\"secret\"}}"})
    void malformedProviderResponsesAndAllInvalidCandidatesAreGatewayErrors(String body) {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, body);
        MapillaryException error = assertStatus(HttpStatus.BAD_GATEWAY,
                () -> fixture.service.findReference(LAT, LON));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void rejectsMissingTokenBeforeCallingProvider() {
        Fixture fixture = fixture(" ");
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> fixture.service.findReference(LAT, LON));
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -91, 91})
    void rejectsInvalidLatitudeWithoutProviderCall(double latitude) {
        Fixture fixture = fixture(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.findReference(latitude, LON));
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.NEGATIVE_INFINITY, -181, 181})
    void rejectsInvalidLongitudeWithoutProviderCall(double longitude) {
        Fixture fixture = fixture(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.findReference(LAT, longitude));
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 302})
    void mapsProviderHttpErrorsWithoutLeakingCredentials(int status) {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {}).andRespond(withStatus(HttpStatus.valueOf(status))
                .body("provider-secret " + TOKEN));
        MapillaryException error = assertStatus(status == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,
                () -> fixture.service.findReference(LAT, LON));
        assertFalse(error.getMessage().contains(TOKEN));
        assertFalse(error.getMessage().contains("provider-secret"));
    }

    @Test
    void mapsGraphRateLimitErrorEvenOnHttp200() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, "{\"error\":{\"code\":4,\"message\":\"secret\"}}");
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> fixture.service.findReference(LAT, LON));
    }

    @Test
    void mapsGraphRateLimitErrorOnHttp403() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {}).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":4,\"message\":\"secret\"}}"));
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> fixture.service.findReference(LAT, LON));
    }

    @Test
    void handlesProviderTimeout() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {}).andRespond(withException(new SocketTimeoutException()));
        assertStatus(HttpStatus.GATEWAY_TIMEOUT, () -> fixture.service.findReference(LAT, LON));
    }

    @Test
    void missingOrInvalidGeometryAndFutureCaptureAreRejected() {
        Fixture fixture = fixture(TOKEN);
        String future = image("1", LAT, Instant.now().plusSeconds(172800).toEpochMilli());
        String invalid = image("2", 100, NEWER);
        respond(fixture, search(future, invalid));
        assertStatus(HttpStatus.BAD_GATEWAY, () -> fixture.service.findReference(LAT, LON));
    }

    @Test
    void splitsDateLineBoundingBoxRatherThanSearchingWholeWorld() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> assertTrue(request.getURI().getQuery().contains("180.00000000")))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        fixture.server.expect(request -> assertTrue(request.getURI().getQuery().contains("-180.00000000")))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertStatus(HttpStatus.NOT_FOUND, () -> fixture.service.findReference(0, 180));
        fixture.server.verify();
    }

    @Test
    void proxiesThumbnailWithNoGraphTokenOnCdnRequest() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {
            assertEquals("/123", request.getURI().getPath());
            assertEquals("OAuth " + TOKEN, request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess("{\"thumb_1024_url\":\"https://scontent.test.fbcdn.net/ref.jpg\"}",
                MediaType.APPLICATION_JSON));
        fixture.server.expect(request -> {
            assertEquals("scontent.test.fbcdn.net", request.getURI().getHost());
            assertNull(request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess(validImage(), MediaType.IMAGE_PNG));
        MapillaryService.ReferenceImage image = fixture.service.getReferenceImage("123");
        assertArrayEquals(validImage(), image.bytes());
        assertEquals(MediaType.IMAGE_PNG, image.mediaType());
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://scontent.test.fbcdn.net/ref.jpg", "https://untrusted.test/ref.jpg",
            "https://scontent.test.fbcdn.net.evil.test/ref.jpg", "https://scontent.test.fbcdn.net/ref.jpg?access_token=mock-mapillary-token"})
    void rejectsUnsafeThumbnailUrlsBeforeFetchingThem(String url) {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, "{\"thumb_1024_url\":\"" + url + "\"}");
        assertStatus(HttpStatus.BAD_GATEWAY, () -> fixture.service.getReferenceImage("123"));
        fixture.server.verify();
    }

    @Test
    void rejectsNonImageCdnResponse() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, "{\"thumb_1024_url\":\"https://scontent.test.fbcdn.net/ref.jpg\"}");
        fixture.server.expect(request -> {}).andRespond(withSuccess("secret html", MediaType.TEXT_HTML));
        assertStatus(HttpStatus.BAD_GATEWAY, () -> fixture.service.getReferenceImage("123"));
    }

    @Test
    void rejectsInvalidImageIdAndInvalidConfiguration() {
        Fixture fixture = fixture(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.getReferenceImage("../secrets"));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
                () -> new MapillaryService(RestClient.create(), TOKEN, 0, 100).findReference(LAT, LON));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
                () -> new MapillaryService(RestClient.create(), TOKEN, 1000, 501).findReference(LAT, LON));
        fixture.server.verify();
    }

    private static String image(String id, double latitude, long capturedAt) {
        return """
                {"id":"%s","thumb_1024_url":"https://scontent.test.fbcdn.net/ref.jpg",
                 "captured_at":%d,"computed_geometry":{"type":"Point","coordinates":[20,%s]},
                 "camera_type":"perspective","computed_compass_angle":45,"creator":{"username":"test-contributor"}}
                """.formatted(id, capturedAt, Double.toString(latitude));
    }

    @Test
    void shuffledDatasetHasIdenticalTopFiveRankReasonsAndFingerprints() {
        String[] images = {image("6", 10.0006, NEWER), image("4", 10.0004, NEWER),
                image("1", 10.0001, OLDER), image("3", 10.0003, NEWER),
                image("2", 10.0002, NEWER), image("5", 10.0005, NEWER)};
        Fixture first = fixture(TOKEN), second = fixture(TOKEN);
        respond(first, search(images));
        accessibleImages(first, 5);
        respond(second, search(images[4], images[1], images[5], images[0], images[3], images[2]));
        accessibleImages(second, 5);
        MapillaryReferences a = first.service.findReferences(LAT, LON);
        MapillaryReferences b = second.service.findReferences(LAT, LON);
        assertEquals(a, b);
        assertEquals(List.of("1", "2", "3", "4", "5"), a.candidates().stream().map(MapillaryReference::imageId).toList());
        assertEquals(5, a.imagesScreened());
        assertEquals(1, a.candidatesNotScreened());
        assertEquals(64, a.datasetFingerprint().length());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, a.candidates().get(i).rank());
            assertEquals("UNVERIFIED_SCENE", a.candidates().get(i).sceneState());
            assertFalse(a.candidates().get(i).rankingReasons().isEmpty());
        }
        first.server.verify(); second.server.verify();
    }

    @Test
    void imageIdTieBreakerAndConflictingDuplicatesAreStable() {
        String newer = image("2", LAT, NEWER), older = image("2", LAT, OLDER);
        Fixture first = fixture(TOKEN), second = fixture(TOKEN);
        respond(first, search(newer, image("10", LAT, NEWER), older));
        accessibleImages(first, 2);
        respond(second, search(older, image("10", LAT, NEWER), newer));
        accessibleImages(second, 2);
        MapillaryReferences a = first.service.findReferences(LAT, LON), b = second.service.findReferences(LAT, LON);
        assertEquals(a, b);
        assertEquals(List.of("10", "2"), a.candidates().stream().map(MapillaryReference::imageId).toList());
        assertEquals(1, a.duplicatesRemoved());
        assertEquals(Instant.ofEpochMilli(NEWER), a.candidates().get(1).capturedAt());
    }

    @Test
    void distanceBandBoundariesAreInclusiveAndConfigurable() {
        MapillaryService service = fixture(TOKEN).service;
        double[] distances = {0, 30, 30.001, 75, 75.001, 125, 125.001, 250, 500};
        String[] bands = {"STRONG_PROXIMITY", "STRONG_PROXIMITY", "GOOD_PROXIMITY", "GOOD_PROXIMITY",
                "WEAK_PROXIMITY", "WEAK_PROXIMITY", "VERY_WEAK_PROXIMITY", "VERY_WEAK_PROXIMITY", "VERY_WEAK_PROXIMITY"};
        for (int i = 0; i < distances.length; i++) assertEquals(bands[i], service.proximityClass(distances[i]));
        assertEquals("GOOD_PROXIMITY", new MapillaryService(RestClient.create(), TOKEN, 1000, 100, 5, 10, 20).proximityClass(6));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> new MapillaryService(RestClient.create(), TOKEN,
                1000, 100, 30, 20, 125).findReferences(LAT, LON));
    }

    @Test
    void topEndpointReturnsEmptyDatasetWhileSingleEndpointRetains404() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, search());
        MapillaryReferences results = fixture.service.findReferences(LAT, LON);
        assertTrue(results.candidates().isEmpty());
        assertEquals(0, results.imagesScreened());
        fixture.server.verify();
    }

    @Test
    void skipsFailedOrInvalidImageAndReturnsNextCandidateWithRejectionReasons() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, search(image("1", LAT, NEWER), image("2", 10.0001, NEWER), image("3", 10.0002, NEWER)));
        fixture.server.expect(request -> assertNull(request.getHeaders().getFirst("Authorization")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        fixture.server.expect(request -> {}).andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_JPEG));
        accessibleImages(fixture, 1);
        MapillaryReferences results = fixture.service.findReferences(LAT, LON);
        assertEquals("3", results.candidates().get(0).imageId());
        assertEquals(2, results.rejectedImages().size());
        assertTrue(results.rejectedImages().get(1).reason().contains("Unreadable"));
        assertFalse(results.toString().contains(TOKEN));
        assertFalse(results.toString().contains("fbcdn.net"));
        fixture.server.verify();
    }

    @Test
    void sequenceAndOptionalMetadataAreReturnedOnlyWhenActuallyPresent() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, search(image("1", LAT, NEWER).replace("\"camera_type\"", "\"sequence\":\"real-sequence\",\"camera_type\""),
                image("2", 10.0001, NEWER).replace("\"computed_compass_angle\":45", "\"computed_compass_angle\":999")));
        accessibleImages(fixture, 2);
        List<MapillaryReference> candidates = fixture.service.findReferences(LAT, LON).candidates();
        assertEquals("real-sequence", candidates.get(0).sequenceId());
        assertNull(candidates.get(1).sequenceId());
        assertNull(candidates.get(1).compassAngle());
    }

    @Test
    void sanitizesOptionalProviderTextWithoutReturningMockCredential() {
        Fixture fixture = fixture(TOKEN);
        respond(fixture, search(image("1", LAT, NEWER)
                .replace("test-contributor", TOKEN).replace("perspective", TOKEN)
                .replace("\"camera_type\"", "\"sequence\":\"" + TOKEN + "\",\"camera_type\"")));
        accessibleImages(fixture, 1);
        MapillaryReferences results = fixture.service.findReferences(LAT, LON);
        assertNull(results.candidates().get(0).creator());
        assertNull(results.candidates().get(0).sequenceId());
        assertEquals("unknown", results.candidates().get(0).cameraType());
        assertFalse(results.toString().contains(TOKEN));
    }

    @Test
    void selectedReferenceIsResolvedByIdAndRevalidatedAgainstRequestedGps() {
        Fixture fixture = fixture(TOKEN);
        fixture.server.expect(request -> {
            assertEquals("/7", request.getURI().getPath());
            assertFalse(request.getURI().toString().contains(TOKEN));
        }).andRespond(withSuccess(image("7", 10.0001, NEWER), MediaType.APPLICATION_JSON));
        accessibleImages(fixture, 1);
        MapillaryReference result = fixture.service.resolveReference("7", LAT, LON);
        assertEquals("7", result.imageId());
        assertEquals("UNVERIFIED_SCENE", result.sceneState());
        fixture.server.verify();
    }

    @Test
    void selectedReferenceRejectsOutOfRadiusAndMismatchedProviderId() {
        Fixture far = fixture(TOKEN);
        respond(far, image("7", 10.01, NEWER));
        assertThrows(IllegalArgumentException.class, () -> far.service.resolveReference("7", LAT, LON));
        Fixture wrong = fixture(TOKEN);
        respond(wrong, image("8", LAT, NEWER));
        assertStatus(HttpStatus.BAD_GATEWAY, () -> wrong.service.resolveReference("7", LAT, LON));
        Fixture invalid = fixture(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> invalid.service.resolveReference("../image", LAT, LON));
        invalid.server.verify();
    }

    @Test
    void screeningIsBoundedAndDoesNotTurnRateLimitIntoSuccess() {
        Fixture fixture = fixture(TOKEN);
        String[] images = new String[20];
        for (int i = 0; i < 20; i++) images[i] = image(Integer.toString(i + 1), LAT, NEWER);
        respond(fixture, search(images));
        for (int i = 0; i < 15; i++) fixture.server.expect(request -> {}).andRespond(withStatus(HttpStatus.NOT_FOUND));
        MapillaryReferences result = fixture.service.findReferences(LAT, LON);
        assertTrue(result.candidates().isEmpty());
        assertEquals(15, result.imagesScreened());
        assertEquals(5, result.candidatesNotScreened());
        fixture.server.verify();
        Fixture limited = fixture(TOKEN);
        respond(limited, search(image("1", LAT, NEWER)));
        limited.server.expect(request -> {}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> limited.service.findReferences(LAT, LON));
    }

    private static void accessibleImages(Fixture fixture, int count) {
        for (int i = 0; i < count; i++) fixture.server.expect(request -> {
            assertEquals("scontent.test.fbcdn.net", request.getURI().getHost());
            assertNull(request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess(validImage(), MediaType.IMAGE_PNG));
    }

    static byte[] validImage() {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, (x * 4 << 16) | y * 4);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", bytes);
            return bytes.toByteArray();
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
    }

    private static String search(String... images) {
        return "{\"data\":[" + String.join(",", images) + "]}";
    }

    private static void respond(Fixture fixture, String body) {
        fixture.server.expect(request -> {}).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static MapillaryException assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable action) {
        MapillaryException exception = assertThrows(MapillaryException.class, action);
        assertEquals(expected, exception.getStatus());
        return exception;
    }

    private static Fixture fixture(String token) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new MapillaryService(builder.build(), token, 10_000, 100), server);
    }

    private record Fixture(MapillaryService service, MockRestServiceServer server) { }
}
