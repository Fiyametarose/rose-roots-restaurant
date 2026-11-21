package com.roseroots.server.settings;

import jakarta.validation.constraints.*;
import java.util.List;

final class BrandingData {
    static final String LOGO_PATH = "/api/v1/settings/logo/";
    static final String DEFAULT_LOGO = LOGO_PATH + "default";

    private BrandingData() {}

    enum Theme {
        FOREST_GOLD("#0F3D29", "#F7F2E8", "#A77936"),
        WINE_GOLD("#5A2537", "#FBF2E9", "#A77936"),
        SLATE_GOLD("#243B45", "#F1F4EF", "#A77936");

        final String primary;
        final String background;
        final String accent;

        Theme(String primary, String background, String accent) {
            this.primary = primary;
            this.background = background;
            this.accent = accent;
        }

        Palette palette() { return new Palette(name(), primary, background, accent); }
    }

    record Palette(String preset, String primary, String background, String accent) {}
    record Brand(String name, String descriptor, String tagline, String description, String telephone,
                 String email, String address, String logoReference, Palette theme, String currency,
                 List<String> languages, List<String> serviceModes) {}
    record ManagerSettings(long version, Brand brand, List<Palette> themes) {}

    record Update(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 120) String name,
                  @Size(max = 240) String tagline, @Size(max = 1500) String description,
                  @Size(max = 40) @Pattern(regexp = "[+0-9() .-]*") String telephone,
                  @Size(max = 254) @Email String email, @Size(max = 500) String address,
                  @Size(max = 80) String logoReference, @NotNull Theme theme) {}
}
