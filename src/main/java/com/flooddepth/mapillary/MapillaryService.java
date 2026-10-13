package com.flooddepth.mapillary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

@Service
public class MapillaryService {
    private static final String API_ROOT = "https://graph.mapillary.com";
    private static final String FIELDS = "id,thumb_1024_url,captured_at,computed_geometry,geometry,"
            + "camera_type,computed_compass_angle,compass_angle,creator,sequence";
    private static final int CANDIDATE_LIMIT = 100;
    private static final int TOP_LIMIT = 5;
    private static final int SCREEN_LIMIT = 15;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final String SELECTION =
            "Proximity band, exact distance, newest capture, computed geometry, lexicographic image ID";
    private static final String SUITABILITY =
            "Nearby historical imagery only. Road, direction, scene match and dry conditions "
            + "are unverified. Panorama thumbnails may be distorted. Search is bounded.";

    private final RestClient restClient;
    private final String accessToken;
    private final int timeoutMs;
    private final double radiusMeters;
    private final double strongMeters;
    private final double goodMeters;
    private final double weakMeters;

    @Autowired
    public MapillaryService(RestClient.Builder builder,
            @Value("${mapillary.access-token:}") String accessToken,
            @Value("${mapillary.timeout-ms:10000}") int timeoutMs,
            @Value("${mapillary.search-radius-meters:100}") double radiusMeters,
            @Value("${mapillary.proximity.strong-meters:30}") double strongMeters,
            @Value("${mapillary.proximity.good-meters:75}") double goodMeters,
            @Value("${mapillary.proximity.weak-meters:125}") double weakMeters) {
        this(createRestClient(builder, timeoutMs), accessToken, timeoutMs, radiusMeters,
                strongMeters, goodMeters, weakMeters);
    }

    MapillaryService(RestClient client, String token, int timeoutMs, double radiusMeters) {
        this(client, token, timeoutMs, radiusMeters, 30, 75, 125);
    }

    MapillaryService(RestClient client, String token, int timeoutMs, double radiusMeters,
            double strongMeters, double goodMeters, double weakMeters) {
        this.restClient = client;
        this.accessToken = token;
        this.timeoutMs = timeoutMs;
        this.radiusMeters = radiusMeters;
        this.strongMeters = strongMeters;
        this.goodMeters = goodMeters;
        this.weakMeters = weakMeters;
    }

    public MapillaryReference findReference(double latitude, double longitude) {
        MapillaryReferences results = findReferences(latitude, longitude);
        if (results.candidates().isEmpty()) {
            throw new MapillaryException(HttpStatus.NOT_FOUND,
                    "No accessible reference image available within " + radiusMeters + " meters.");
        }
        return results.candidates().get(0);
    }

    /** Revalidate a user's selected ID without depending on a later, possibly different search dataset. */
    public MapillaryReference resolveReference(String imageId, double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        validateConfiguration();
        if (imageId == null || !imageId.matches("[0-9]{1,32}")) {
            throw new IllegalArgumentException("A numeric Mapillary image ID is required.");
        }
        Candidate candidate = parseCandidate(requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/" + imageId)
                .queryParam("fields", FIELDS).build().toUri()), latitude, longitude);
        if (candidate == null || !candidate.id().equals(imageId)) throw providerError("Selected image has invalid Mapillary metadata.");
        if (candidate.distance() > radiusMeters) throw new IllegalArgumentException("Selected reference is outside the configured search radius.");
        downloadImage(candidate.thumbnail());
        return toReference(candidate, latitude, longitude, 1, 1, false, 1);
    }

    public MapillaryReferences findReferences(double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        validateConfiguration();
        List<Candidate> candidates = new ArrayList<>();
        int examined = 0;
        int invalid = 0;
        boolean truncated = false;
        for (String bbox : boundingBoxes(latitude, longitude)) {
            URI uri = UriComponentsBuilder.fromUriString(API_ROOT + "/images")
                    .queryParam("bbox", bbox).queryParam("fields", FIELDS)
                    .queryParam("limit", CANDIDATE_LIMIT).build().encode().toUri();
            JsonNode response = requestMetadata(uri);
            JsonNode data = response.get("data");
            if (data == null || !data.isArray()) {
                throw providerError("Mapillary returned malformed image search data.");
            }
            truncated |= data.size() >= CANDIDATE_LIMIT
                    || response.path("paging").hasNonNull("next");
            for (JsonNode image : data) {
                examined++;
                Candidate candidate = parseCandidate(image, latitude, longitude);
                if (candidate == null) {
                    invalid++;
                } else if (candidate.distance() <= radiusMeters) {
                    candidates.add(candidate);
                }
            }
        }
        if (examined > 0 && invalid == examined) {
            throw providerError("Mapillary image candidates are missing valid required metadata.");
        }
        Comparator<Candidate> order = Comparator.comparingInt((Candidate c) -> proximityBand(c.distance()))
                .thenComparingDouble(Candidate::distance)
                .thenComparing(Candidate::capturedAt, Comparator.reverseOrder())
                .thenComparing(c -> !"computed_geometry".equals(c.geometrySource()))
                .thenComparing(Candidate::id);
        // Canonical tie-break handles conflicting duplicate IDs independently of response order.
        TreeMap<String, Candidate> unique = new TreeMap<>();
        Comparator<Candidate> duplicateOrder = order.thenComparing(Candidate::canonical)
                .thenComparing(c -> c.thumbnail().toString());
        for (Candidate candidate : candidates) {
            unique.merge(candidate.id(), candidate,
                    (a, b) -> duplicateOrder.compare(a, b) <= 0 ? a : b);
        }
        List<Candidate> ranked = unique.values().stream().sorted(order).toList();
        String dataset = fingerprint(ranked.stream().map(Candidate::canonical).toList());
        List<MapillaryReference> accepted = new ArrayList<>();
        List<MapillaryReferences.RejectedImage> rejected = new ArrayList<>();
        List<String> screening = new ArrayList<>();
        int screened = 0;
        for (Candidate candidate : ranked) {
            if (screened >= SCREEN_LIMIT || accepted.size() >= TOP_LIMIT) break;
            screened++;
            try {
                ReferenceImage image = downloadImage(candidate.thumbnail());
                screening.add(candidate.id() + ":accepted:" + digest(image.bytes()));
                accepted.add(toReference(candidate, latitude, longitude, examined, ranked.size(),
                        truncated, accepted.size() + 1));
            } catch (MapillaryException exception) {
                // A rate limit affects the whole operation; do not turn it into an empty success.
                if (exception.getStatus() == HttpStatus.TOO_MANY_REQUESTS) throw exception;
                rejected.add(new MapillaryReferences.RejectedImage(candidate.id(), exception.getMessage()));
                screening.add(candidate.id() + ":rejected:" + exception.getMessage());
            }
        }
        return new MapillaryReferences(List.copyOf(accepted), latitude, longitude, radiusMeters,
                examined, ranked.size(), candidates.size() - ranked.size(), screened,
                ranked.size() - screened, truncated, dataset, fingerprint(screening), SELECTION,
                SUITABILITY, List.copyOf(rejected));
    }

    private MapillaryReference toReference(Candidate selected, double latitude, double longitude,
            int examined, int valid, boolean truncated, int rank) {
        return new MapillaryReference(selected.id(),
                "/api/mapillary/reference/" + selected.id() + "/image",
                selected.capturedAt(), selected.latitude(), selected.longitude(), selected.distance(),
                "Mapillary", selected.geometrySource(), selected.cameraType(), selected.compassAngle(),
                selected.creator(), "https://www.mapillary.com/app/?pKey=" + selected.id(),
                latitude, longitude, radiusMeters, examined, valid, truncated,
                SELECTION, SUITABILITY, selected.sequenceId(), proximityClass(selected.distance()),
                "UNVERIFIED_SCENE", rank,
                List.of("Proximity: " + proximityClass(selected.distance()),
                        "Exact distance ascending: " + Double.toString(selected.distance()) + " m",
                        "Capture descending: " + selected.capturedAt(),
                        "Geometry: " + selected.geometrySource(), "Image ID tie-break: " + selected.id(),
                        "Image accessible and structurally decodable; scene/blur/occlusion unverified"),
                "ACCESSIBLE_STRUCTURALLY_VALID");
    }

    /** Metadata-only discovery. Never follows provider URLs or downloads twenty images for ranking. */
    public MapillaryReferences discoverReferences(double latitude, double longitude) {
        return discoverReferences(latitude, longitude, 20);
    }

    /** Diagnostic projection of the bounded pool, without changing normal selection limits. */
    public MapillaryReferences auditDiscovery(double latitude, double longitude) {
        return discoverReferences(latitude, longitude, 604);
    }

    /** Exact provider metadata, including out-of-radius ground truth. Does not authorize selection. */
    public MapillaryReference referenceMetadata(String imageId, double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        validateConfiguration();
        if (imageId == null || !imageId.matches("[0-9]{1,32}")) {
            throw new IllegalArgumentException("A numeric Mapillary image ID is required.");
        }
        Candidate candidate = parseCandidate(requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/" + imageId)
                .queryParam("fields", FIELDS).build().toUri()), latitude, longitude);
        if (candidate == null || !candidate.id().equals(imageId)) throw providerError("Requested image has invalid Mapillary metadata.");
        MapillaryReference r = toReference(candidate, latitude, longitude, 1, 1, false, 0);
        return new MapillaryReference(r.imageId(), r.imageUrl(), r.capturedAt(), r.latitude(), r.longitude(),
                r.distanceMeters(), r.source(), r.geometrySource(), r.cameraType(), r.compassAngle(), r.creator(),
                r.attributionUrl(), latitude, longitude, radiusMeters, 1, 1, false,
                "Exact-ID diagnostic metadata only; not automatically selected", SUITABILITY, r.sequenceId(),
                r.proximityClass(), "UNVERIFIED_SCENE", 0,
                List.of("Exact-ID diagnostic ground truth; radius eligibility is not implied"), "NOT_SCREENED");
    }

    private MapillaryReferences discoverReferences(double latitude, double longitude, int outputLimit) {
        validateCoordinates(latitude, longitude);
        validateConfiguration();
        List<Candidate> raw = new ArrayList<>();
        int examined = 0;
        boolean truncated = false;
        List<String> core = radiusMeters > weakMeters
                ? boundingBoxes(latitude, longitude, Math.min(radiusMeters, goodMeters)) : List.of();
        List<String> boxes = new ArrayList<>(core);
        boxes.addAll(boundingBoxes(latitude, longitude));
        for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
            if (examined >= 600) { truncated = true; break; }
            String box = boxes.get(boxIndex);
            String after = null;
            int pageLimit = boxIndex < core.size() ? 1 : 3;
            for (int page = 0; page < pageLimit; page++) {
                var builder = UriComponentsBuilder.fromUriString(API_ROOT + "/images")
                        .queryParam("bbox", box).queryParam("fields", FIELDS).queryParam("limit", CANDIDATE_LIMIT);
                if (after != null) builder.queryParam("after", after);
                JsonNode response = requestMetadata(builder.build().encode().toUri());
                JsonNode data = response.path("data");
                if (!data.isArray()) throw providerError("Mapillary returned malformed image search data.");
                for (JsonNode image : data) {
                    if (examined >= 600) break;
                    examined++;
                    Candidate candidate = parseCandidate(image, latitude, longitude);
                    if (candidate != null && candidate.distance() <= radiusMeters) raw.add(candidate);
                }
                String cursor = response.path("paging").path("cursors").path("after").asText("");
                boolean more = response.path("paging").hasNonNull("next");
                truncated |= (data.size() >= CANDIDATE_LIMIT && !more)
                        || more && (page == pageLimit-1 || cursor.equals(after) || !cursor.matches("[A-Za-z0-9_=-]{1,2048}"));
                if (!more || !cursor.matches("[A-Za-z0-9_=-]{1,2048}") || cursor.equals(after)) break;
                after = cursor;
            }
        }
        List<Candidate> initial = discoveryOrder(raw);
        // Two distinct sequence anchors, one frame on each side, at most six extra Graph calls.
        var sequences = new java.util.HashSet<String>();
        var known = new java.util.HashSet<String>();
        raw.forEach(c -> known.add(c.id()));
        for (Candidate anchor : initial) {
            if (sequences.size() >= 2) break;
            if (anchor.sequenceId() == null || !anchor.sequenceId().matches("[A-Za-z0-9_-]{1,200}")
                    || !sequences.add(anchor.sequenceId())) continue;
            try {
                JsonNode response = requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/image_ids")
                        .queryParam("sequence_id", anchor.sequenceId()).queryParam("limit", 1000).build().encode().toUri());
                JsonNode ids = response.path("data");
                if (!ids.isArray()) { truncated = true; continue; }
                truncated |= response.path("paging").hasNonNull("next") || ids.size() >= 1000;
                int index = -1;
                for (int i = 0; i < Math.min(1000, ids.size()); i++) {
                    if (anchor.id().equals(ids.get(i).path("id").asText())) { index = i; break; }
                }
                if (index < 0) { truncated = true; continue; }
                for (int offset : new int[]{-1, 1}) {
                    int i = index + offset;
                    if (i < 0 || i >= Math.min(1000, ids.size())) continue;
                    String id = ids.get(i).path("id").asText("");
                    if (!id.matches("[0-9]{1,32}") || !known.add(id)) continue;
                    Candidate neighbor = parseCandidate(requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/" + id)
                            .queryParam("fields", FIELDS).build().toUri()), latitude, longitude);
                    examined++;
                    if (neighbor != null && anchor.sequenceId().equals(neighbor.sequenceId())
                            && neighbor.distance() <= radiusMeters) raw.add(neighbor);
                }
            } catch (MapillaryException exception) {
                if (exception.getStatus() == HttpStatus.TOO_MANY_REQUESTS) throw exception;
                // Optional sequence discovery cannot erase valid spatial results.
                truncated = true;
            }
        }
        List<Candidate> ranked = discoveryOrder(raw);
        List<MapillaryReference> pool = new ArrayList<>();
        String method = "Bounded spatial and sequence metadata; up to five diverse viewpoints, then local distance/capture/ID order";
        for (Candidate candidate : ranked.stream().limit(outputLimit).toList()) {
            MapillaryReference r = toReference(candidate, latitude, longitude, examined, ranked.size(), truncated, pool.size() + 1);
            pool.add(new MapillaryReference(r.imageId(), r.imageUrl(), r.capturedAt(), r.latitude(), r.longitude(),
                    r.distanceMeters(), r.source(), r.geometrySource(), r.cameraType(), r.compassAngle(), r.creator(),
                    r.attributionUrl(), latitude, longitude, radiusMeters, examined, ranked.size(), truncated,
                    method, SUITABILITY, r.sequenceId(), r.proximityClass(), "UNVERIFIED_SCENE", r.rank(),
                    List.of("Up to five diverse sequence/45-degree heading viewpoints, then nearby frames retained",
                            "Unknown uploaded heading: no claimed direction alignment", "Distance: " + r.distanceMeters(),
                            "Capture: " + r.capturedAt(), "Stable image ID tie-break: " + r.imageId()), "NOT_SCREENED"));
        }
        return new MapillaryReferences(List.copyOf(pool), latitude, longitude, radiusMeters, examined, ranked.size(),
                raw.size() - ranked.size(), 0, ranked.size(), truncated,
                fingerprint(ranked.stream().map(Candidate::canonical).toList()), fingerprint(List.of()), method, SUITABILITY, List.of());
    }

    private List<Candidate> discoveryOrder(List<Candidate> raw) {
        Comparator<Candidate> order = Comparator.comparingInt((Candidate c) -> proximityBand(c.distance()))
                .thenComparingDouble(Candidate::distance).thenComparing(Candidate::capturedAt, Comparator.reverseOrder())
                .thenComparing(c -> !"computed_geometry".equals(c.geometrySource())).thenComparing(Candidate::id);
        TreeMap<String, Candidate> unique = new TreeMap<>();
        Comparator<Candidate> canonical = order.thenComparing(Candidate::canonical).thenComparing(c -> c.thumbnail().toString());
        raw.forEach(c -> unique.merge(c.id(), c, (a, b) -> canonical.compare(a, b) <= 0 ? a : b));
        List<Candidate> remaining = new ArrayList<>(unique.values().stream().sorted(order).toList());
        List<Candidate> ranked = new ArrayList<>();
        var used = new java.util.HashSet<String>();
        while (!remaining.isEmpty() && ranked.size() < 5) {
            Candidate selected = remaining.stream().filter(c -> !used.contains(viewGroup(c))).findFirst().orElse(null);
            if (selected == null) break;
            ranked.add(selected); used.add(viewGroup(selected)); remaining.remove(selected);
        }
        // Coverage is a bounded seed, not an unlimited penalty on adjacent local viewpoints.
        // At most five seeds precede any otherwise distance-ranked frame.
        ranked.addAll(remaining);
        return List.copyOf(ranked);
    }

    private static String viewGroup(Candidate c) {
        // Heading is used for coverage, not to invent the uploaded photograph's orientation.
        return String.valueOf(c.sequenceId()) + ":" + (c.compassAngle() == null ? "unknown" : (int)(c.compassAngle() / 45))
                ;
    }

    /** Close sequence fallback; every returned frame still needs an independent scene assessment. */
    public List<MapillaryReference> closeSequenceNeighbors(MapillaryReference anchor, double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        validateConfiguration();
        if (anchor.sequenceId() == null || !anchor.sequenceId().matches("[A-Za-z0-9_-]{1,200}")) return List.of();
        JsonNode ids = requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/image_ids")
                .queryParam("sequence_id", anchor.sequenceId()).queryParam("limit", 1000).build().encode().toUri()).path("data");
        if (!ids.isArray()) throw providerError("Mapillary returned malformed sequence data.");
        int index=-1;
        for (int i=0;i<Math.min(1000,ids.size());i++) {
            if (anchor.imageId().equals(ids.get(i).path("id").asText())) { index=i; break; }
        }
        if(index<0) return List.of();
        List<MapillaryReference> neighbors=new ArrayList<>();
        for(int offset:new int[]{-1,1}) {
            int i=index+offset;
            if(i<0 || i>=Math.min(1000,ids.size())) continue;
            String id=ids.get(i).path("id").asText("");
            if(!id.matches("[0-9]{1,32}") || id.equals(anchor.imageId())) continue;
            Candidate c=parseCandidate(requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT+"/"+id)
                    .queryParam("fields",FIELDS).build().toUri()),latitude,longitude);
            if(c==null || !id.equals(c.id()) || !anchor.sequenceId().equals(c.sequenceId())
                    || c.distance()>radiusMeters || distance(anchor.latitude(),anchor.longitude(),c.latitude(),c.longitude())>15
                    || Math.abs(java.time.Duration.between(anchor.capturedAt(),c.capturedAt()).toMillis())>5000
                    || !anchor.cameraType().equals(c.cameraType()) || anchor.compassAngle()==null || c.compassAngle()==null) continue;
            double angle=Math.abs(anchor.compassAngle()-c.compassAngle());
            if(Math.min(angle,360-angle)>15) continue;
            downloadImage(c.thumbnail());
            neighbors.add(toReference(c,latitude,longitude,1,1,false,neighbors.size()+1));
        }
        return List.copyOf(neighbors);
    }

    /** Resolve a fresh signed thumbnail URL server-side so neither token nor CDN URL reaches React. */
    public ReferenceImage getReferenceImage(String imageId) {
        if (imageId == null || !imageId.matches("[0-9]{1,32}")) {
            throw new IllegalArgumentException("A numeric Mapillary image ID is required.");
        }
        validateConfiguration();
        JsonNode metadata = requestMetadata(UriComponentsBuilder.fromUriString(API_ROOT + "/" + imageId)
                .queryParam("fields", "thumb_1024_url").build().toUri());
        URI thumbnail = thumbnailUri(metadata.path("thumb_1024_url").asText(""));
        if (thumbnail == null) {
            throw providerError("Mapillary returned no valid reference thumbnail URL.");
        }
        return downloadImage(thumbnail);
    }

    private ReferenceImage downloadImage(URI thumbnail) {
        try {
            // The Graph token is deliberately omitted from this CDN request.
            return restClient.get().uri(thumbnail).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw providerStatus(response.getStatusCode().value());
                }
                MediaType type = response.getHeaders().getContentType();
                if (type == null || !(MediaType.IMAGE_JPEG.isCompatibleWith(type)
                        || MediaType.IMAGE_PNG.isCompatibleWith(type))) {
                    throw providerError("Mapillary thumbnail response is not a supported image.");
                }
                byte[] bytes = response.getBody().readNBytes(5 * 1024 * 1024 + 1);
                if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) {
                    throw providerError("Mapillary thumbnail is empty or too large.");
                }
                MapillaryImageValidator.validate(bytes);
                return new ReferenceImage(bytes, type);
            });
        } catch (ResourceAccessException exception) {
            throw timeoutError();
        } catch (RestClientException exception) {
            throw providerError("Could not retrieve the Mapillary reference image.");
        }
    }

    public static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "Latitude must be finite and between -90 and 90; longitude between -180 and 180.");
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(accessToken)) {
            throw new MapillaryException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MAPILLARY_ACCESS_TOKEN is not configured on the backend.");
        }
        if (timeoutMs <= 0 || !Double.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > 500) {
            throw new MapillaryException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mapillary timeout must be positive and search radius must be between 0 and 500 meters.");
        }
        if (!Double.isFinite(strongMeters) || !Double.isFinite(goodMeters) || !Double.isFinite(weakMeters)
                || strongMeters <= 0 || goodMeters <= strongMeters || weakMeters <= goodMeters) {
            throw new MapillaryException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mapillary proximity thresholds must be finite, positive and increasing.");
        }
    }

    private JsonNode requestMetadata(URI uri) {
        try {
            JsonNode response = restClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "OAuth " + accessToken.trim())
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.isObject()) {
                throw providerError("Mapillary returned an empty or malformed response.");
            }
            if (response.has("error")) {
                if (isRateLimit(response)) throw providerStatus(429);
                throw providerError("Mapillary reported a provider error; check backend token permissions.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            // Graph can report rate limits with HTTP 400/403 as well as HTTP 429.
            try {
                if (isRateLimit(exception.getResponseBodyAs(JsonNode.class))) throw providerStatus(429);
            } catch (RestClientException | IllegalStateException ignored) {
                // An unreadable error body still maps from the provider's HTTP status.
            }
            throw providerStatus(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw timeoutError();
        } catch (RestClientException exception) {
            throw providerError("Mapillary request failed or returned unreadable JSON.");
        }
    }

    private Candidate parseCandidate(JsonNode image, double latitude, double longitude) {
        String id = image.path("id").asText("");
        JsonNode captured = image.path("captured_at");
        if (!id.matches("[0-9]{1,32}") || !captured.isIntegralNumber() || !captured.canConvertToLong()
                || captured.longValue() <= 0
                || thumbnailUri(image.path("thumb_1024_url").asText("")) == null) {
            return null;
        }
        Instant capturedAt = Instant.ofEpochMilli(captured.longValue());
        if (capturedAt.isAfter(Instant.now().plusSeconds(86400))) return null;
        String geometrySource = "computed_geometry";
        JsonNode point = image.path(geometrySource);
        if (!validPoint(point)) {
            geometrySource = "geometry";
            point = image.path(geometrySource);
        }
        if (!validPoint(point)) return null;
        double imageLon = point.path("coordinates").get(0).doubleValue();
        double imageLat = point.path("coordinates").get(1).doubleValue();
        JsonNode heading = image.path("computed_compass_angle");
        if (!heading.isNumber()) heading = image.path("compass_angle");
        Double angle = heading.isNumber() && Double.isFinite(heading.doubleValue())
                && heading.doubleValue() >= 0 && heading.doubleValue() < 360 ? heading.doubleValue() : null;
        return new Candidate(id, capturedAt, imageLat, imageLon,
                distance(latitude, longitude, imageLat, imageLon), geometrySource,
                cameraType(image.path("camera_type")), angle,
                safeText(image.path("creator").path("username")), sequenceId(image.path("sequence")),
                thumbnailUri(image.path("thumb_1024_url").asText("")));
    }

    private String safeText(JsonNode value) {
        String text = value.isString() ? value.asText() : null;
        if (text == null || text.length() > 200 || text.contains("\n") || text.contains("\r")
                || (StringUtils.hasText(accessToken) && text.contains(accessToken.trim()))) return null;
        return text;
    }

    private String sequenceId(JsonNode sequence) {
        return safeText(sequence.isObject() ? sequence.path("id") : sequence);
    }

    private static String cameraType(JsonNode value) {
        String type = value.asText("unknown");
        return List.of("perspective", "fisheye", "spherical").contains(type) ? type : "unknown";
    }

    int proximityBand(double meters) {
        if (meters <= strongMeters) return 0;
        if (meters <= goodMeters) return 1;
        if (meters <= weakMeters) return 2;
        return 3;
    }

    String proximityClass(double meters) {
        return switch (proximityBand(meters)) {
            case 0 -> "STRONG_PROXIMITY";
            case 1 -> "GOOD_PROXIMITY";
            case 2 -> "WEAK_PROXIMITY";
            default -> "VERY_WEAK_PROXIMITY";
        };
    }

    private static String fingerprint(List<String> values) {
        return digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private static boolean validPoint(JsonNode point) {
        JsonNode coordinates = point.path("coordinates");
        if (!"Point".equals(point.path("type").asText()) || !coordinates.isArray()
                || coordinates.size() < 2 || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
            return false;
        }
        try {
            validateCoordinates(coordinates.get(1).doubleValue(), coordinates.get(0).doubleValue());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private URI thumbnailUri(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            // Mapillary v4 thumbnails are served from Meta's signed CDN, never arbitrary hosts.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !host.toLowerCase(Locale.ROOT).endsWith(".fbcdn.net")
                    || uri.getFragment() != null
                    || (StringUtils.hasText(accessToken) && value.contains(accessToken.trim()))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> boundingBoxes(double latitude, double longitude) {
        return boundingBoxes(latitude, longitude, radiusMeters);
    }

    private List<String> boundingBoxes(double latitude, double longitude, double searchRadius) {
        double angular = searchRadius / EARTH_RADIUS_METERS;
        double latDelta = Math.toDegrees(angular);
        double south = Math.max(-90, latitude - latDelta);
        double north = Math.min(90, latitude + latDelta);
        double lonDelta = south == -90 || north == 90 ? 180
                : Math.toDegrees(Math.asin(Math.min(1, Math.sin(angular) / Math.cos(Math.toRadians(latitude)))));
        if (lonDelta == 180) return List.of(bbox(-180, south, 180, north));
        double west = longitude - lonDelta;
        double east = longitude + lonDelta;
        if (west < -180) return List.of(bbox(west + 360, south, 180, north), bbox(-180, south, east, north));
        if (east > 180) return List.of(bbox(west, south, 180, north), bbox(-180, south, east - 360, north));
        return List.of(bbox(west, south, east, north));
    }

    private static String bbox(double west, double south, double east, double north) {
        return String.format(Locale.ROOT, "%.8f,%.8f,%.8f,%.8f", west, south, east, north);
    }

    private static double distance(double lat, double lon, double imageLat, double imageLon) {
        double a = Math.pow(Math.sin(Math.toRadians(imageLat - lat) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(imageLat))
                * Math.pow(Math.sin(Math.toRadians(imageLon - lon) / 2), 2);
        return EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    private static RestClient createRestClient(RestClient.Builder builder, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Math.max(1, timeoutMs));
        factory.setReadTimeout(Math.max(1, timeoutMs));
        return builder.clone().requestFactory(factory).build();
    }

    private static MapillaryException providerStatus(int status) {
        if (status == 429) return new MapillaryException(HttpStatus.TOO_MANY_REQUESTS,
                "Mapillary rate limit reached. Please retry later.");
        if (status == 401 || status == 403) return providerError(
                "Mapillary rejected backend authentication; check token permissions.");
        return providerError("Mapillary provider request failed (HTTP " + status + ").");
    }

    private static boolean isRateLimit(JsonNode response) {
        if (response == null) return false;
        int code = response.path("error").path("code").asInt(0);
        return code == 4 || code == 17 || code == 32 || code == 613;
    }

    private static MapillaryException providerError(String message) {
        return new MapillaryException(HttpStatus.BAD_GATEWAY, message);
    }

    private static MapillaryException timeoutError() {
        return new MapillaryException(HttpStatus.GATEWAY_TIMEOUT,
                "Mapillary could not be reached within the configured timeout.");
    }

    private record Candidate(String id, Instant capturedAt, double latitude, double longitude,
            double distance, String geometrySource, String cameraType, Double compassAngle, String creator,
            String sequenceId, URI thumbnail) {
        String canonical() {
            // Length-prefix strings so provider text cannot create ambiguous fingerprint input.
            return java.util.Arrays.asList(id, capturedAt.toString(), Double.toString(latitude), Double.toString(longitude),
                    Double.toString(distance), geometrySource, cameraType, String.valueOf(compassAngle),
                    creator, sequenceId).stream()
                    .map(value -> value == null ? "N;" : "S" + value.length() + ":" + value)
                    .collect(java.util.stream.Collectors.joining());
        }
    }

    public record ReferenceImage(byte[] bytes, MediaType mediaType) { }
}
