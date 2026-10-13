package com.flooddepth.depth;

/** Public application error contract. Never carries provider bodies or exception messages. */
public record DepthError(String status, String code, String message) {
    public DepthError(String code, String message) { this("ERROR", code, message); }
}
