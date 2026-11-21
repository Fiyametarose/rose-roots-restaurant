package com.roseroots.server.settings;

import java.util.Currency;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.roseroots.server.settings.BrandingData.*;

@Service
@Profile("catalog")
class BrandingService {
    private static final List<Palette> THEMES = java.util.Arrays.stream(Theme.values()).map(Theme::palette).toList();
    private final RestaurantSettingsRepository settings;
    private final RestaurantSettingsAuditRepository audit;
    private final LogoService logos;
    private final String currency;

    BrandingService(RestaurantSettingsRepository settings, RestaurantSettingsAuditRepository audit,
                    LogoService logos, @Value("${rose.catalog.currency}") String currency) {
        this.settings = settings;
        this.audit = audit;
        this.logos = logos;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
    }

    @Transactional(readOnly = true)
    ManagerSettings current() {
        var row = settings.findById(1).orElseThrow();
        return new ManagerSettings(row.version, brand(row), THEMES);
    }

    @Transactional
    ManagerSettings update(Update input, String actor) {
        var row = settings.lockCurrent().orElseThrow();
        if (row.version != input.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Settings changed. Reload before saving again.");
        }
        if (input.telephone() != null && !input.telephone().isBlank()
                && input.telephone().chars().filter(Character::isDigit).count() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telephone must include at least five digits.");
        }

        var previous = brand(row);
        var logoReference = optional(input.logoReference());
        row.logoId = logos.requireReference(logoReference);
        row.defaultLogo = DEFAULT_LOGO.equals(logoReference);
        row.name = input.name().strip();
        row.tagline = optional(input.tagline());
        row.description = optional(input.description());
        row.telephone = optional(input.telephone());
        row.email = optional(input.email());
        row.address = optional(input.address());
        row.theme = input.theme();
        var updated = brand(row);
        if (!previous.equals(updated)) {
            audit.save(new RestaurantSettingsAudit(actor, previous, updated));
            settings.flush();
        }
        return new ManagerSettings(row.version, updated, THEMES);
    }

    private Brand brand(RestaurantSettings row) {
        String logo = row.defaultLogo ? DEFAULT_LOGO : row.logoId == null ? null : LOGO_PATH + row.logoId;
        return new Brand(row.name, "Restaurant", row.tagline, row.description, row.telephone,
                row.email, row.address, logo, row.theme.palette(), currency, List.of("en", "am", "ar", "pga"),
                List.of("DINE_IN", "TAKEAWAY"));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
