package org.simplepoint.installation.host;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;
import org.simplepoint.platform.installation.InstallationFingerprint;

/** Pins the executable installer and all of its packaged domain adapters, excluding credentials. */
final class InstallationArtifact {
  private InstallationArtifact() {}

  static String fingerprint() throws Exception {
    var artifacts = new TreeMap<String, String>();
    for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
      Path path = Path.of(entry);
      String name = path.getFileName().toString();
      if (!name.endsWith(".jar") || !Files.isRegularFile(path)) {
        throw new IllegalArgumentException("Installation requires the complete packaged JAR distribution");
      }
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          digest.update(buffer, 0, count);
        }
      }
      if (artifacts.putIfAbsent(name, HexFormat.of().formatHex(digest.digest())) != null) {
        throw new IllegalArgumentException("Duplicate installation artifact name");
      }
    }
    if (artifacts.isEmpty()) {
      throw new IllegalArgumentException("Empty installation distribution");
    }
    var values = new ArrayList<String>();
    artifacts.forEach((name, digest) -> {
      values.add(name);
      values.add(digest);
    });
    return InstallationFingerprint.of(values.toArray(String[]::new));
  }
}
