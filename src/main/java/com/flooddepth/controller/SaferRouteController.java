package com.flooddepth.controller;

import com.flooddepth.saferoute.*;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routes")
public final class SaferRouteController {
    private final SaferRouteService service;
    public SaferRouteController(SaferRouteService service){this.service=service;}
    @PostMapping("/safer") public Response plan(@RequestBody Request request){return service.plan(request);}
    public record Error(String code,String message){ }
    @ExceptionHandler(RoutePlanningException.class) ResponseEntity<Error> failure(RoutePlanningException e){return ResponseEntity.status(e.code().status).body(new Error(e.code().name(),e.code().message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Error> invalid(Exception e){return ResponseEntity.badRequest().body(new Error("INVALID_START","Please check the starting coordinates."));}
    @ExceptionHandler(Exception.class) ResponseEntity<Error> unexpected(Exception e){return ResponseEntity.status(503).body(new Error("ROUTING_UNAVAILABLE","Route planning is temporarily unavailable. Please try again."));}
}
