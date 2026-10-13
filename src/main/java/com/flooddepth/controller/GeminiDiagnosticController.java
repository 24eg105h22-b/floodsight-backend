package com.flooddepth.controller;

import com.flooddepth.ai.GeminiFloodVisionClient;
import com.flooddepth.ai.GeminiFloodVisionClient.DiagnosticStage;
import com.flooddepth.exception.AiConfigurationException;
import com.flooddepth.scene.SceneImageValidator;
import com.flooddepth.service.ImageStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Opt-in local diagnostics. Never accepts arbitrary prompts, credentials, URLs or server file paths. */
@RestController
@RequestMapping("/api/gemini/diagnostics")
@ConditionalOnProperty(name = "gemini.diagnostics.enabled", havingValue = "true")
public class GeminiDiagnosticController {
    private final ObjectProvider<GeminiFloodVisionClient> provider;
    private final ImageStorageService storage;
    public GeminiDiagnosticController(ObjectProvider<GeminiFloodVisionClient> provider, ImageStorageService storage) {
        this.provider = provider;
        this.storage = storage;
    }

    public record Request(DiagnosticStage stage, String imagePath) { }
    public record ContractRequest(GeminiFloodVisionClient.ContractStage stage, String imagePath) { }

    private void local(HttpServletRequest http) {
        if (!java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(http.getRemoteAddr()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @GetMapping("/model")
    public ResponseEntity<?> model(HttpServletRequest http) {
        local(http);
        var client = provider.getIfAvailable();
        if (client == null) throw new AiConfigurationException("Enable Gemini before diagnostics");
        var result = client.modelMetadata();
        return ResponseEntity.status(result.success() ? 200 : 502).body(result);
    }

    @PostMapping("/contract")
    public ResponseEntity<?> contract(@RequestBody ContractRequest request, HttpServletRequest http) {
        local(http);
        if (request == null || request.stage() == null) throw new IllegalArgumentException("Contract stage required");
        var client = provider.getIfAvailable();
        if (client == null) throw new AiConfigurationException("Enable Gemini before diagnostics");
        byte[] bytes = null; String mime = null;
        if (request.stage() == GeminiFloodVisionClient.ContractStage.SMALL_IMAGE) {
            var stored = storage.loadStoredImage(request.imagePath());
            try {
                if (java.nio.file.Files.size(stored.path()) > 65_536) throw new IllegalArgumentException("Diagnostic fixture too large");
            } catch (java.io.IOException exception) { throw new IllegalArgumentException("Diagnostic fixture unavailable"); }
            bytes = SceneImageValidator.read(stored); mime = stored.mediaType();
        } else if (request.imagePath() != null) throw new IllegalArgumentException("Text contract probes do not accept images");
        var result = client.diagnoseContract(request.stage(), bytes, mime);
        return ResponseEntity.status(result.success() ? 200 : 502).body(result);
    }

    @PostMapping
    public ResponseEntity<?> diagnose(@RequestBody Request request, HttpServletRequest http) {
        // Do not trust Forwarded/X-Forwarded-For headers; no CORS grant for this diagnostic API.
        if (!java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(http.getRemoteAddr()))
            return ResponseEntity.status(403).body(Map.of("error", "Gemini diagnostics are available only over loopback."));
        if (request == null || request.stage() == null) throw new IllegalArgumentException("Diagnostic stage is required.");
        GeminiFloodVisionClient client = provider.getIfAvailable();
        if (client == null) throw new AiConfigurationException("Enable Gemini on the backend before diagnostic calls.");
        byte[] bytes = null;
        String mime = null;
        if (request.stage() == DiagnosticStage.IMAGE) {
            var stored = storage.loadStoredImage(request.imagePath());
            bytes = SceneImageValidator.read(stored);
            mime = stored.mediaType();
        } else if (request.imagePath() != null) {
            throw new IllegalArgumentException("Text diagnostic stages do not accept images.");
        }
        var result = client.diagnose(request.stage(), bytes, mime);
        return ResponseEntity.status(result.success() ? 200 : 502).body(result);
    }

    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<?> configuration() {
        return ResponseEntity.status(503).body(Map.of("error", "Gemini is disabled or its credential/model configuration is missing or invalid."));
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Use TEXT, STRUCTURED, or IMAGE; IMAGE requires a valid uploaded JPG/PNG path."));
    }
}
