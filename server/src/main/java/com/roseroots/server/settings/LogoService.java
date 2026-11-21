package com.roseroots.server.settings;

import com.roseroots.server.media.ImageDecoding;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.roseroots.server.settings.BrandingData.LOGO_PATH;

@Service
@Profile("catalog")
class LogoService {
    private static final int UPLOAD_LIMIT = 8 * 1024 * 1024;
    private final RestaurantLogoRepository logos;
    private final Path directory;

    LogoService(RestaurantLogoRepository logos, @Value("${rose.branding.logo-directory}") String directory) {
        this.logos = logos;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional(rollbackFor = IOException.class)
    String upload(InputStream body, long contentLength, String actor) throws IOException {
        if (contentLength > UPLOAD_LIMIT) {
            throw tooLarge();
        }
        byte[] data = body.readNBytes(UPLOAD_LIMIT + 1);
        if (data.length > UPLOAD_LIMIT) {
            throw tooLarge();
        }
        BufferedImage logo = resize(ImageDecoding.decode(data));
        UUID id = UUID.randomUUID();
        Files.createDirectories(directory);
        Path target = directory.resolve(id + ".png");
        Path staging = Files.createTempFile(directory, "logo-", ".tmp");
        try {
            if (!ImageIO.write(logo, "PNG", staging.toFile())) {
                throw new IOException("PNG encoder unavailable.");
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staging);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException failure) {
                        LoggerFactory.getLogger(LogoService.class).warn("Could not remove rolled-back logo {}", id);
                    }
                }
            }
        });
        logos.save(new RestaurantLogo(id, logo.getWidth(), logo.getHeight(), Files.size(target), actor));
        return LOGO_PATH + id;
    }

    UUID requireReference(String reference) {
        if (reference == null || reference.isBlank() || BrandingData.DEFAULT_LOGO.equals(reference)) {
            return null;
        }
        if (reference.startsWith(LOGO_PATH)) {
            UUID id = canonicalId(reference.substring(LOGO_PATH.length()));
            if (id != null && logos.existsById(id)) {
                return id;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an uploaded restaurant logo.");
    }

    byte[] read(String pathId) throws IOException {
        UUID id = canonicalId(pathId);
        if (id == null) {
            throw missing();
        }
        var row = logos.findById(id).orElseThrow(LogoService::missing);
        String filename = id + ".png";
        if (!filename.equals(row.storageReference)) {
            throw missing();
        }
        try (var input = Files.newInputStream(directory.resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
            byte[] logo = input.readNBytes(UPLOAD_LIMIT + 1);
            if (logo.length != row.fileSize || logo.length > UPLOAD_LIMIT) {
                throw missing();
            }
            return logo;
        } catch (java.nio.file.NoSuchFileException absent) {
            throw missing();
        }
    }

    private static UUID canonicalId(String value) {
        try {
            var id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BufferedImage resize(BufferedImage decoded) {
        double ratio = Math.min(1, 1024.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
        int width = Math.max(1, (int) Math.round(decoded.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(decoded.getHeight() * ratio));
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(decoded, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Logo exceeds the 8 MiB limit.");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant logo not found.");
    }
}
