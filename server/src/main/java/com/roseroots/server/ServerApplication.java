package com.roseroots.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class ServerApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--showcase-bootstrap-hash".equals(args[0])) {
            printBootstrapHash();
            return;
        }
        SpringApplication.run(ServerApplication.class, args);
    }

    private static void printBootstrapHash() {
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            byte[] bytes = Base64.getDecoder().decode(input.readLine());
            String password = new String(bytes, StandardCharsets.UTF_8);
            if (password.length() < 12 || bytes.length > 72 || password.indexOf(0) >= 0) {
                throw new IllegalArgumentException();
            }
            System.out.println(new BCryptPasswordEncoder().encode(password));
        } catch (Exception invalid) {
            System.err.println("Supply a bootstrap password of at least 12 characters and at most 72 UTF-8 bytes through the installer.");
            System.exit(2);
        }
    }
}
