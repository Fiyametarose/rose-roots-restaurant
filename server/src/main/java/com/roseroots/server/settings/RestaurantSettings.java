package com.roseroots.server.settings;

import jakarta.persistence.*;
import java.util.UUID;
import static com.roseroots.server.settings.BrandingData.*;

@Entity
@Table(name = "restaurant_settings")
class RestaurantSettings {
    @Id int id;
    @Version long version;
    String name;
    String tagline;
    String description;
    String telephone;
    String email;
    String address;
    @Enumerated(EnumType.STRING) Theme theme;
    UUID logoId;
    boolean defaultLogo;

    protected RestaurantSettings() {}
}
