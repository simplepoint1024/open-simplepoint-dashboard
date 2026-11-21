package org.simplepoint.installation.host;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Bounded owner-only installer inputs and exclusive publication of reviewed local plans. */
final class InstallationFiles {
  private InstallationFiles() {}

  static byte[] read(Path path, int limit) throws Exception {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
            .anyMatch(
                permission ->
                    permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_"))) {
      throw new IllegalArgumentException("Installation input must be an owner-only regular file");
    }
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) {
        throw new IllegalArgumentException("Installation input exceeds limit");
      }
      return bytes;
    }
  }

  static void create(Path path, byte[] contents) throws Exception {
    try (var output =
        java.nio.channels.FileChannel.open(
            path,
            java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
      var bytes = java.nio.ByteBuffer.wrap(contents);
      while (bytes.hasRemaining()) {
        output.write(bytes);
      }
      output.force(true);
    }
    // The reviewed UUIDs must survive publication of the directory entry before target DDL starts.
    try (var directory =
        java.nio.channels.FileChannel.open(
            path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }
}
