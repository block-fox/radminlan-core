package dev.radminlan.vendor.openradclient;

import dev.radminlan.vendor.openradplugin.crypto.Identity;
import dev.radminlan.vendor.openradplugin.session.CoordinatorEndpoint;
import dev.radminlan.vendor.openradplugin.session.ServiceConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Imports a device identity while the destination profile lock is held. */
final class ProfileIdentityMigration {
    private static final String IDENTITY = "identity.json";
    private static final String PENDING = "registration.pending";

    private ProfileIdentityMigration() {}

    static void importIfNeeded(CoordinatorEndpoint destination, Path legacyProfile) throws IOException {
        Path directory = destination.identityDirectory();
        Path identity = directory.resolve(IDENTITY);
        if (legacyProfile == null || !Files.notExists(identity, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(directory.resolve(PENDING), LinkOption.NOFOLLOW_LINKS)) return;

        CoordinatorEndpoint legacy = CoordinatorEndpoint.load(legacyProfile, ServiceConfig.BOOTSTRAP, ServiceConfig.PORT);
        Path source = legacy.identityDirectory();
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS) || Files.isSameFile(source, directory)) return;
        Path sourceIdentity = source.resolve(IDENTITY);
        Path sourceIntent = source.resolve(PENDING);
        Path sourceLock = source.resolve("identity.lock");
        if (Files.notExists(sourceIdentity, LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(sourceIntent, LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(sourceLock, LinkOption.NOFOLLOW_LINKS)) return;

        try (FileChannel channel = FileChannel.open(sourceLock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Legacy profile is in use");
            if (Files.notExists(sourceIdentity, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.notExists(sourceIntent, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Legacy identity registration is unresolved");
                }
                return;
            }
            if (!destination.bootstrap().equals(legacy.bootstrap()) || destination.port() != legacy.port()
                    || !Arrays.equals(destination.modulus(), legacy.modulus())) {
                throw new IOException("Legacy profile uses a different coordinator");
            }
            try (var oldIdentity = Identity.load(sourceIdentity)) {
                Path oldChoices = source.resolve(NetworkChoices.FILE_NAME);
                Path newChoices = directory.resolve(NetworkChoices.FILE_NAME);
                if (!Files.notExists(oldChoices, LinkOption.NOFOLLOW_LINKS)
                        && Files.notExists(newChoices, LinkOption.NOFOLLOW_LINKS)) {
                    NetworkChoices.save(newChoices, NetworkChoices.load(oldChoices));
                }
                // Commit the identity last so an interrupted import can be retried.
                oldIdentity.save(identity);
            }
        }
    }
}
