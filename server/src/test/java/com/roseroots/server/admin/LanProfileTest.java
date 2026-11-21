package com.roseroots.server.admin;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("lan")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LanProfileTest {
    @TempDir
    static Path temporary;

    private static final String STORE_PASSWORD = UUID.randomUUID().toString();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void tlsSettings(DynamicPropertyRegistry registry) throws Exception {
        Path store = temporary.resolve("server.p12");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        Path output = temporary.resolve("keytool.txt");
        Process generator = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=127.0.0.1",
                "-ext", "SAN=IP:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", STORE_PASSWORD, "-noprompt").redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        if (!generator.waitFor(20, TimeUnit.SECONDS)) {
            generator.destroyForcibly();
            throw new IllegalStateException("Timed out generating the test TLS certificate.");
        }
        if (generator.exitValue() != 0) {
            throw new IllegalStateException(Files.readString(output));
        }
        registry.add("ROSE_SERVER_ADDRESS", () -> "127.0.0.1");
        registry.add("ROSE_SERVER_KEY_STORE", () -> store.toUri().toString());
        registry.add("ROSE_SERVER_KEY_STORE_PASSWORD", () -> STORE_PASSWORD);
    }

    @Test
    void lanProfileServesTrustedHttpsAndMarksTheAdminSessionCookieSecure() throws Exception {
        KeyStore serverStore = KeyStore.getInstance("PKCS12");
        try (InputStream stream = Files.newInputStream(temporary.resolve("server.p12"))) {
            serverStore.load(stream, STORE_PASSWORD.toCharArray());
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("server", serverStore.getCertificate("server"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);

        try (HttpClient client = HttpClient.newBuilder().sslContext(tls).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + AdminSecurity.CSRF))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.sslSession().isPresent());
            String cookie = response.headers().firstValue("Set-Cookie").orElseThrow()
                    .toLowerCase(Locale.ROOT);
            assertTrue(cookie.contains("; secure"), cookie);
            assertTrue(cookie.contains("; httponly"), cookie);
        }
    }
}
