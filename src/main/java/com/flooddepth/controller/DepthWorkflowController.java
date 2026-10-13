package com.flooddepth.controller;

import com.flooddepth.depth.DepthResult;
import com.flooddepth.depth.DepthWorkflowService;
import com.flooddepth.depth.DepthError;
import com.flooddepth.depth.DepthDiagnostics;
import com.flooddepth.exception.*;
import com.flooddepth.mapillary.MapillaryException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/flood")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class DepthWorkflowController {
    private final DepthWorkflowService workflow;
    public DepthWorkflowController(DepthWorkflowService workflow) { this.workflow = workflow; }
    @PostMapping(value = "/estimate-depth", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DepthResult estimate(@RequestParam("file") MultipartFile file, @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude) {
        return workflow.estimate(file, latitude, longitude);
    }
    @ExceptionHandler(MapillaryException.class)
    ResponseEntity<DepthError> mapillary(MapillaryException e) {
        DepthDiagnostics.failed("MAPILLARY_UNAVAILABLE", null);
        return error(e.getStatus().value(), "MAPILLARY_UNAVAILABLE", "Historical street imagery could not be retrieved. Try again later or check the photo coordinates.");
    }
    @ExceptionHandler(GeminiFailureException.class)
    ResponseEntity<DepthError> gemini(GeminiFailureException e) {
        DepthDiagnostics.failed(e.code().name(), e.providerHttpStatus());
        return error(502, e.code().name(), e.isRateLimit() ? "Flood analysis is temporarily busy. Please try again shortly."
                : "The image comparison service could not produce a valid assessment. Please try again later.");
    }
    @ExceptionHandler({AiConfigurationException.class})
    ResponseEntity<DepthError> configuration(Exception e) {
        DepthDiagnostics.failed("CONFIGURATION_UNAVAILABLE", null);
        return error(503, "ANALYSIS_UNAVAILABLE", "Image analysis is unavailable. The backend administrator must check the provider configuration.");
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    ResponseEntity<DepthError> invalid(Exception e) {
        DepthDiagnostics.failed("INVALID_INPUT", null);
        return error(400, "INVALID_INPUT", "Provide a valid JPG/PNG (64–8192 pixels, at most 32 megapixels and 10 MB), latitude -90 to 90, and longitude -180 to 180.");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<DepthError> failure(Exception e) {
        DepthDiagnostics.failed("INTERNAL_EXCEPTION", null);
        // Fixed messages only: never serialize provider bodies, exception causes, configuration or credentials.
        return error(500, "DEPTH_ANALYSIS_FAILED", "The estimate could not be completed or saved. Please try again later.");
    }
    private static ResponseEntity<DepthError> error(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new DepthError(code, message));
    }
}
