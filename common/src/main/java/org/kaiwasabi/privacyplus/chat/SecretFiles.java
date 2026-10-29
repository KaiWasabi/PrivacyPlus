package org.kaiwasabi.privacyplus.chat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

// Writes files holding keys and passphrases: readable by the owner only where the file system has POSIX
// permissions (Windows keeps config/ in the user's profile), flushed to disk, and replaced in one step, so a
// crash or power cut mid-write never leaves half a key file (or none) behind.
final class SecretFiles {
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private SecretFiles() {
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        if (posix(file)) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(temp);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        // Files written by older versions had default permissions
        if (posix(file)) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        }
        syncDirectory(file.toAbsolutePath().getParent());
    }

    // So the rename itself survives a power cut. Not possible everywhere (Windows can't open a directory).
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // best effort
        }
    }

    private static boolean posix(Path file) {
        return file.toAbsolutePath().getParent().getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
