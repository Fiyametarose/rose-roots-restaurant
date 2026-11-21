package com.roseroots.server.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.roseroots.server.settings.BrandingData.*;

@RestController
@Profile("catalog")
class BrandingController {
    private final BrandingService settings;
    private final LogoService logos;

    BrandingController(BrandingService settings, LogoService logos) {
        this.settings = settings;
        this.logos = logos;
    }

    @GetMapping("/api/v1/settings/public")
    ResponseEntity<Brand> publicSettings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(settings.current().brand());
    }

    @GetMapping("/api/v1/admin/settings")
    ResponseEntity<ManagerSettings> managerSettings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.current());
    }

    @PatchMapping("/api/v1/admin/settings")
    ManagerSettings update(@Valid @RequestBody Update update, Principal manager) {
        return settings.update(update, manager.getName());
    }

    @PostMapping("/api/v1/admin/settings/logo")
    ResponseEntity<Map<String, String>> upload(HttpServletRequest request, Principal manager) throws IOException {
        String reference = logos.upload(request.getInputStream(), request.getContentLengthLong(), manager.getName());
        return ResponseEntity.created(URI.create(reference)).body(Map.of("logoReference", reference));
    }

    @GetMapping("/api/v1/settings/logo/{id}")
    ResponseEntity<byte[]> logo(@PathVariable("id") String id) throws IOException {
        boolean bundled = id.equals("default");
        byte[] logo = bundled ? new ClassPathResource("branding/rose-mark.png").getContentAsByteArray() : logos.read(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options", "nosniff")
                .cacheControl(bundled ? CacheControl.noCache() : CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(logo);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> problem(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", failure.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException failure) {
        var field = failure.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(Map.of("message", field == null ? "Check the settings fields."
                : field.getField() + " " + field.getDefaultMessage()));
    }
}
