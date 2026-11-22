package com.roseroots.admin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class AdminApi {
    private final URI base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http;

    static String configuredAddress(String adminAddress, String sharedAddress) {
        if (adminAddress != null && !adminAddress.isBlank()) {
            return adminAddress.trim();
        }
        return sharedAddress == null || sharedAddress.isBlank()
                ? "http://127.0.0.1:8080" : sharedAddress.trim();
    }

    AdminApi(String address) {
        this(address, null);
    }

    AdminApi(String address, javax.net.ssl.SSLContext tls) {
        base = URI.create(address);
        String host = base.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
        boolean secure = "https".equalsIgnoreCase(base.getScheme());
        if (host == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || !(secure || loopback && "http".equalsIgnoreCase(base.getScheme()))) {
            throw new IllegalArgumentException("Configure an HTTPS Admin server URL.");
        }
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(4));
        if (tls != null) client.sslContext(tls);
        http = client.build();
    }

    void login(String username, String password) throws IOException, InterruptedException {
        try {
            String token = csrf();
            var request = endpoint("login").header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(username)
                            + "&password=" + encode(password)));
            send(request, 204);
        } catch (IOException | InterruptedException failure) {
            discard();
            throw failure;
        }
    }

    JsonObject dashboard() throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("dashboard").GET(), 200)).getAsJsonObject();
    }

    JsonObject catalog() throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("catalog").GET(), 200)).getAsJsonObject();
    }

    JsonObject settings() throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("settings").GET(), 200)).getAsJsonObject();
    }

    JsonObject saveSettings(JsonObject payload) throws IOException, InterruptedException {
        var request = endpoint("settings").header("X-CSRF-TOKEN", csrf())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()));
        return JsonParser.parseString(send(request, 200)).getAsJsonObject();
    }

    String uploadLogo(byte[] bytes) throws IOException, InterruptedException {
        var request = endpoint("settings/logo").timeout(Duration.ofSeconds(25))
                .header("X-CSRF-TOKEN", csrf()).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        return JsonParser.parseString(send(request, 201)).getAsJsonObject().get("logoReference").getAsString();
    }

    JsonObject reviews(String query) throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("reviews?" + query).GET(), 200)).getAsJsonObject();
    }

    JsonObject reviewFollowUp(java.util.UUID id, JsonObject payload) throws IOException, InterruptedException {
        var request = endpoint("reviews/" + id + "/follow-up").header("X-CSRF-TOKEN", csrf())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()));
        return JsonParser.parseString(send(request, 200)).getAsJsonObject();
    }

    com.google.gson.JsonArray staff() throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("staff").GET(), 200)).getAsJsonArray();
    }

    JsonObject saveStaff(String id, JsonObject payload) throws IOException, InterruptedException {
        var request = endpoint(id == null ? "staff" : "staff/" + id).header("X-CSRF-TOKEN", csrf())
                .header("Content-Type", "application/json").method(id == null ? "POST" : "PATCH",
                        HttpRequest.BodyPublishers.ofString(payload.toString()));
        return JsonParser.parseString(send(request, id == null ? 201 : 200)).getAsJsonObject();
    }

    JsonObject priorityQueue(String cursor) throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("kitchen/priority" + (cursor == null ? "" : "?cursor=" + encode(cursor)))
                .GET(), 200)).getAsJsonObject();
    }

    JsonObject changePriority(String id, String marker, JsonObject body) throws IOException, InterruptedException {
        var request = endpoint("orders/" + id + "/" + marker).header("X-CSRF-TOKEN", csrf())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return JsonParser.parseString(send(request, 200)).getAsJsonObject();
    }

    com.google.gson.JsonArray kitchenConfiguration(String resource) throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint(resource).GET(), 200)).getAsJsonArray();
    }

    JsonObject orders(OperationsPresentation.Filters filters, String cursor) throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("orders?" + filters.query(cursor)).GET(), 200)).getAsJsonObject();
    }

    JsonObject order(java.util.UUID id) throws IOException, InterruptedException {
        return JsonParser.parseString(send(endpoint("orders/" + id).GET(), 200)).getAsJsonObject();
    }

    void writeCatalog(String method, String path, JsonObject body) throws IOException, InterruptedException {
        var request = endpoint(path).header("X-CSRF-TOKEN", csrf())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        send(request, method.equals("POST") ? 201 : 200);
    }

    String imageUrl(String reference) {
        try {
            URI candidate = base.resolve(reference);
            if (candidate.getUserInfo() == null && base.getScheme().equalsIgnoreCase(candidate.getScheme())
                    && base.getRawAuthority().equalsIgnoreCase(candidate.getRawAuthority())) {
                return candidate.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // An invalid image reference leaves the placeholder visible.
        }
        return "";
    }

    String uploadPhoto(byte[] image) throws IOException, InterruptedException {
        var request = endpoint("menu-images").timeout(Duration.ofSeconds(25))
                .header("X-CSRF-TOKEN", csrf()).header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image));
        return JsonParser.parseString(send(request, 201)).getAsJsonObject().get("imageReference").getAsString();
    }

    java.awt.image.BufferedImage readPhoto(String reference) throws IOException, InterruptedException {
        String url = imageUrl(reference);
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Choose a new photo to replace this image reference.");
        }
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("The saved photo could not be loaded. Choose a replacement.");
            }
            return PhotoProcessing.read(body);
        }
    }

    void logout() throws IOException, InterruptedException {
        try {
            send(endpoint("logout").header("X-CSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.noBody()), 204);
        } finally {
            discard();
        }
    }

    private void discard() {
        cookies.getCookieStore().removeAll();
        http.shutdown();
    }

    private String csrf() throws IOException, InterruptedException {
        return send(endpoint("csrf").GET(), 200);
    }

    private HttpRequest.Builder endpoint(String path) {
        return HttpRequest.newBuilder(base.resolve("/api/v1/admin/" + path))
                .timeout(Duration.ofSeconds(8));
    }

    private String send(HttpRequest.Builder request, int expected) throws IOException, InterruptedException {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new Rejected(response.statusCode(), response.body());
        }
        return response.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static final class Rejected extends IOException {
        final int status;
        String code = "";

        Rejected(int status, String body) {
            this.status = status;
            if (status == 503) {
                try {
                    code = CatalogControls.text(JsonParser.parseString(body).getAsJsonObject(), "code");
                } catch (RuntimeException ignored) {
                    // A generic intermediary failure does not have a problem code.
                }
            }
            if (status == 400 || status == 409 || status == 413 || status == 415) {
                try {
                    detail = JsonParser.parseString(body).getAsJsonObject().get("message").getAsString();
                } catch (RuntimeException ignored) {
                    detail = "";
                }
            }
        }

        String detail = "";
    }
}
