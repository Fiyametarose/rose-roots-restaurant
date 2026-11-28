package com.roseroots.customer.services;

import com.roseroots.customer.models.TerminalDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

/** Installation settings supplied outside the application by the tablet operator. */
public final class LocalDeviceConfiguration {
    private LocalDeviceConfiguration() {}

    public static Path defaultPath() {
        String override = System.getenv("ROSE_ROOTS_TERMINAL_CONFIG");
        return override == null || override.isBlank()
                ? Path.of(System.getProperty("user.home"), ".rose-roots", "terminal.properties")
                : Path.of(override);
    }

    public static TerminalDevice load(Path path) throws IOException {
        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.writeString(path, "device.id=" + UUID.randomUUID() + System.lineSeparator(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException alreadyCreated) {
                // A second instance initialized the same installation.
            }
        }
        var properties = new Properties();
        try (var input = Files.newBufferedReader(path)) {
            properties.load(input);
        }
        String id = property(properties, "device.id");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Terminal configuration requires device.id");
        }
        String tableId = property(properties, "table.id");
        String number = property(properties, "table.displayNumber");
        if (tableId.isEmpty() != number.isEmpty()) {
            throw new IllegalArgumentException("A table assignment requires both table.id and table.displayNumber");
        }
        return new TerminalDevice(id,
                tableId.isEmpty() ? null : new TerminalDevice.TableAssignment(tableId, number), false);
    }

    private static String property(Properties properties, String name) {
        return properties.getProperty(name, "").strip();
    }
}
