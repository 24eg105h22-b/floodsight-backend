package com.flooddepth.mapillary;

import java.util.List;

/** Audit metadata contains no provider URLs or credentials. */
public record MapillaryReferences(
        List<MapillaryReference> candidates,
        double requestedLatitude, double requestedLongitude, double searchRadiusMeters,
        int candidatesExamined, int metadataValidCandidates, int duplicatesRemoved,
        int imagesScreened, int candidatesNotScreened, boolean searchTruncated,
        String datasetFingerprint, String screeningFingerprint,
        String selectionMethod, String suitabilityNote, List<RejectedImage> rejectedImages) {
    public record RejectedImage(String imageId, String reason) { }
}
