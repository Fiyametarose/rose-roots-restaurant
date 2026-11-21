package com.roseroots.server.catalog;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import com.roseroots.server.media.ImageDecoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("catalog")
class MenuImageService {
    static final int UPLOAD_LIMIT = 8 * 1024 * 1024;
    static final String PUBLIC_PATH = "/api/v1/menu-images/";
    private static final int OUTPUT_EDGE = 1600;
    private static final Logger LOG = LoggerFactory.getLogger(MenuImageService.class);
    private final MenuImageRepository images;
    private final Path directory;

    MenuImageService(MenuImageRepository images, @Value("${rose.catalog.image-directory}") String directory) {
        this.images = images;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional(rollbackFor = IOException.class)
    ImageView upload(InputStream body, long length, String actor) throws IOException {
        return upload(body, length, actor, null);
    }

    ImageView upload(InputStream body, long length, String actor, String sourceSha256) throws IOException {
        if (length > UPLOAD_LIMIT) {
            throw tooLarge();
        }
        byte[] data = body.readNBytes(UPLOAD_LIMIT + 1);
        if (data.length > UPLOAD_LIMIT) {
            throw tooLarge();
        }
        BufferedImage image = normalize(decode(data));
        UUID id = UUID.randomUUID();
        Files.createDirectories(directory);
        Path target = directory.resolve(id + ".jpg");
        Path staging = Files.createTempFile(directory, "upload-", ".tmp");
        try {
            if (!ImageIO.write(image, "JPEG", staging.toFile())) {
                throw new IOException("JPEG encoder unavailable.");
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
                    } catch (IOException error) {
                        LOG.warn("Could not remove rolled-back menu image {}", id, error);
                    }
                }
            }
        });
        var metadata = new MenuImage(id, image.getWidth(), image.getHeight(), Files.size(target), actor);
        metadata.sourceSha256 = sourceSha256;
        MenuImage saved = images.save(metadata);
        return new ImageView(saved.id, PUBLIC_PATH + saved.id, saved.contentType, saved.width, saved.height,
                saved.fileSize, saved.createdAt);
    }

    byte[] read(UUID id) throws IOException {
        MenuImage image = images.findById(id).orElseThrow(MenuImageService::notFound);
        String filename = id + ".jpg";
        if (!filename.equals(image.storageReference)) {
            throw notFound();
        }
        try (var stream = Files.newInputStream(directory.resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = stream.readNBytes(UPLOAD_LIMIT + 1);
            if (data.length != image.fileSize || data.length > UPLOAD_LIMIT) {
                throw notFound();
            }
            return data;
        } catch (java.nio.file.NoSuchFileException missing) {
            throw notFound();
        }
    }

    void validateReference(String candidate, String previous) {
        if (candidate == null || candidate.isBlank() || Objects.equals(candidate.strip(), previous)) {
            return;
        }
        String reference = candidate.strip();
        try {
            UUID id = UUID.fromString(reference.startsWith(PUBLIC_PATH) ? reference.substring(PUBLIC_PATH.length()) : "");
            if ((PUBLIC_PATH + id).equals(reference) && images.existsById(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Reject malformed or unmanaged references.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an uploaded menu photo.");
    }

    static BufferedImage decode(byte[] bytes) {
        return ImageDecoding.decode(bytes);
    }

    private static BufferedImage normalize(BufferedImage image) {
        double scale = Math.min(1.0, OUTPUT_EDGE / (double) Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        var normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo exceeds the 8 MiB limit.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu photo not found.");
    }

    record ImageView(UUID id, String imageReference, String contentType, int width, int height,
                     long fileSize, Instant createdAt) {}
}
