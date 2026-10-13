package com.flooddepth.saferoute;

/** Fixed messages only; provider bodies, URLs, credentials and causes are never retained. */
public final class RoutePlanningException extends RuntimeException {
    public enum Code {
        DESTINATION_NOT_FOUND(404,"We couldn't find that destination. Please try a more specific location."),
        NO_ROUTES(404,"No suitable route could be found."),
        ROUTING_UNAVAILABLE(503,"Route planning is temporarily unavailable. Please try again."),
        INVALID_START(400,"Please check the starting coordinates."),
        INVALID_DESTINATION(400,"Enter a destination to plan your route.");
        public final int status;public final String message;
        Code(int status,String message){this.status=status;this.message=message;}
    }
    private final Code code;
    public RoutePlanningException(Code code){super(code.message);this.code=code;}
    public Code code(){return code;}
}
