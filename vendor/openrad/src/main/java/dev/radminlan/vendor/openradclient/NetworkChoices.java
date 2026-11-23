package dev.radminlan.vendor.openradclient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Per-identity network choices. Passwords and server membership live elsewhere. */
final class NetworkChoices {
    static final String FILE_NAME = "network-choices.properties";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CHOICES = 512;

    private NetworkChoices() {}

    static Map<String, Boolean> load(Path path) throws IOException {
        Properties values = new Properties();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Network choices exceed the profile size limit");
            values.load(new ByteArrayInputStream(bytes));
        } catch (NoSuchFileException missing) {
            return Map.of();
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid network choices", malformed);
        }
        if (values.size() > MAX_CHOICES) throw new IOException("Too many saved network choices");
        Map<String, Boolean> choices = new HashMap<>();
        for (String name : values.stringPropertyNames()) {
            String enabled = values.getProperty(name);
            if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
                    || !enabled.equals("true") && !enabled.equals("false")) {
                throw new IOException("Invalid saved network choice");
            }
            choices.put(name, Boolean.valueOf(enabled));
        }
        return choices;
    }

    static void save(Path path, Map<String, Boolean> choices) throws IOException {
        if (choices.size() > MAX_CHOICES) throw new IOException("Too many saved network choices");
        Properties values = new Properties();
        choices.forEach((name, enabled) -> values.setProperty(name, enabled.toString()));
        Path temporary = Files.createTempFile(path.getParent(), "network-choices-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, "RadminLAN network choices");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
