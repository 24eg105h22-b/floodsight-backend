package com.flooddepth.controller;

import com.flooddepth.mapillary.MapillaryException;
import com.flooddepth.mapillary.MapillaryReference;
import com.flooddepth.mapillary.MapillaryReferences;
import com.flooddepth.mapillary.MapillaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestController
@RequestMapping("/api/mapillary")
@CrossOrigin(origins = "http://localhost:5173")
public class MapillaryController {
    private final MapillaryService service;

    public MapillaryController(MapillaryService service) {
        this.service = service;
    }

    @GetMapping("/reference")
    public MapillaryReference reference(@RequestParam("lat") double latitude,
            @RequestParam("lon") double longitude) {
        MapillaryService.validateCoordinates(latitude, longitude);
        return service.findReference(latitude, longitude);
    }

    @GetMapping("/reference/{imageId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String imageId) {
        MapillaryService.ReferenceImage image = service.getReferenceImage(imageId);
        return ResponseEntity.ok().contentType(image.mediaType())
                .cacheControl(CacheControl.noStore()).body(image.bytes());
    }

    @GetMapping("/references")
    public MapillaryReferences references(@RequestParam("lat") double latitude,
            @RequestParam("lon") double longitude) {
        MapillaryService.validateCoordinates(latitude, longitude);
        return service.findReferences(latitude, longitude);
    }

    @ExceptionHandler(MapillaryException.class)
    public ResponseEntity<Map<String, String>> providerError(MapillaryException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("error", exception.getMessage()));
    }

    /** Development metadata inspection; never offered as a normal user input. */
    @GetMapping("/discovery")
    public MapillaryReferences discovery(@RequestParam("lat") double latitude, @RequestParam("lon") double longitude) {
        return service.discoverReferences(latitude, longitude);
    }

    @GetMapping("/reference/{imageId}/metadata")
    public MapillaryReference metadata(@PathVariable String imageId, @RequestParam("lat") double latitude,
            @RequestParam("lon") double longitude) {
        return service.referenceMetadata(imageId, latitude, longitude);
    }

    @GetMapping("/discovery-audit")
    public MapillaryReferences discoveryAudit(@RequestParam("lat") double latitude, @RequestParam("lon") double longitude) {
        return service.auditDiscovery(latitude, longitude);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> missingOrMalformedCoordinates(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Both lat and lon must be valid numbers."));
    }
}
