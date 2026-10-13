package com.flooddepth.mapillary;

import java.time.Instant;

public record MapillaryReference(
        String imageId,
        String imageUrl,
        Instant capturedAt,
        double latitude,
        double longitude,
        double distanceMeters,
        String source,
        String geometrySource,
        String cameraType,
        Double compassAngle,
        String creator,
        String attributionUrl,
        double requestedLatitude,
        double requestedLongitude,
        double searchRadiusMeters,
        int candidatesExamined,
        int suitableCandidates,
        boolean searchTruncated,
        String selectionMethod,
        String suitabilityNote,
        String sequenceId,
        String proximityClass,
        String sceneState,
        int rank,
        java.util.List<String> rankingReasons,
        String imageValidation) {
}
