package com.roseroots.server.ordering;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

record OrderingTls(Path store, String password, SSLContext trust) {
    static OrderingTls create(Path directory) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path keytool = Files.exists(bin.resolve("keytool.exe")) ? bin.resolve("keytool.exe") : bin.resolve("keytool");
        Path store = directory.resolve("ordering.p12");
        String password = UUID.randomUUID().toString();
        var command = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "ordering",
                "-keyalg", "RSA", "-validity", "2", "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass:env", "ORDERING_TEST_STORE_PASSWORD")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.txt").toFile());
        command.environment().put("ORDERING_TEST_STORE_PASSWORD", password);
        var process = command.start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Keytool did not generate the ordering test certificate.");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Keytool rejected the test certificate configuration.");
        var identity = KeyStore.getInstance("PKCS12");
        try (var bytes = Files.newInputStream(store)) {
            identity.load(bytes, password.toCharArray());
        }
        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("ordering", identity.getCertificate("ordering"));
        var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        manager.init(trusted);
        var context = SSLContext.getInstance("TLS");
        context.init(null, manager.getTrustManagers(), null);
        return new OrderingTls(store, password, context);
    }
}
