package com.roseroots.admin;

import java.io.IOException;
import javax.net.ssl.SSLContext;
import javafx.scene.Parent;

public final class ManagerReviewsFixture {
    private final AdminApi api;

    public ManagerReviewsFixture(String address, SSLContext tls, String user, String password)
            throws IOException, InterruptedException {
        api = new AdminApi(address, tls);
        api.login(user, password);
    }

    public Parent view() { return new ReviewsWorkspace(api, () -> {}, () -> {}); }
}
