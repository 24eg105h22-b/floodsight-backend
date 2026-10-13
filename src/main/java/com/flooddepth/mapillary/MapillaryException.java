package com.flooddepth.mapillary;

import org.springframework.http.HttpStatus;

/** Only safe, application-owned messages belong here; never provider bodies or URLs. */
public class MapillaryException extends RuntimeException {
    private final HttpStatus status;

    public MapillaryException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
