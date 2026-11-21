package com.roseroots.server.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("catalog")
class MenuImageController {
    private final MenuImageService images;

    MenuImageController(MenuImageService images) {
        this.images = images;
    }

    @PostMapping("/api/v1/admin/menu-images")
    ResponseEntity<MenuImageService.ImageView> upload(HttpServletRequest request, Principal manager) throws IOException {
        var image = images.upload(request.getInputStream(), request.getContentLengthLong(), manager.getName());
        return ResponseEntity.created(URI.create(image.imageReference())).body(image);
    }

    @GetMapping("/api/v1/menu-images/{id}")
    ResponseEntity<byte[]> image(@PathVariable("id") UUID id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(images.read(id));
    }
}
