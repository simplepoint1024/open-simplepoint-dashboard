package org.simplepoint.installation.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationFilesTest {
  @TempDir Path directory;

  @Test
  void reviewedPlansArePrivateAndCannotBeSilentlyOverwritten() throws Exception {
    var path = directory.resolve("review.json");
    byte[] bytes = "reviewed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    InstallationFiles.create(path, bytes);
    assertArrayEquals(bytes, InstallationFiles.read(path, 8));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    assertThrows(FileAlreadyExistsException.class, () -> InstallationFiles.create(path, new byte[0]));
    assertArrayEquals(bytes, InstallationFiles.read(path, 8));
  }

  @Test
  void unboundedSharedAndSymlinkInputsAreRejected() throws Exception {
    var path = directory.resolve("review.json");
    InstallationFiles.create(path, new byte[9]);
    assertThrows(IllegalArgumentException.class, () -> InstallationFiles.read(path, 8));
    var link = directory.resolve("link");
    Files.createSymbolicLink(link, path);
    assertThrows(IllegalArgumentException.class, () -> InstallationFiles.read(link, 16));
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r-----"));
    assertThrows(IllegalArgumentException.class, () -> InstallationFiles.read(path, 16));
  }
}
