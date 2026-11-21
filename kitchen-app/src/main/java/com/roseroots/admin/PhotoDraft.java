package com.roseroots.admin;

import java.awt.image.BufferedImage;
import java.io.IOException;

final class PhotoDraft {
    final BufferedImage original;
    final PhotoProcessing.Settings settings;
    final byte[] image;
    private String uploadedReference;

    PhotoDraft(BufferedImage original, PhotoProcessing.Settings settings, byte[] image) {
        this.original = original;
        this.settings = settings;
        this.image = image;
    }

    // A failed dish save can be retried with the same uploaded file.
    synchronized String upload(AdminApi api) throws IOException, InterruptedException {
        if (uploadedReference == null) {
            uploadedReference = api.uploadPhoto(image);
        }
        return uploadedReference;
    }
}
