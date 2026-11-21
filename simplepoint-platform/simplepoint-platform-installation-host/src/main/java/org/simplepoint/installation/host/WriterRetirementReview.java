package org.simplepoint.installation.host;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Exactly one reviewed local executor adapter; unknown schedulers never fall back to an assertion.
 */
record WriterRetirementReview(
    LocalServiceRetirement.Review systemd, DockerContainerRetirement.Review docker) {
  WriterRetirementReview {
    if ((systemd == null) == (docker == null)) {
      throw new IllegalArgumentException("Exactly one writer retirement adapter is required");
    }
  }

  static WriterRetirementReview configured(
      Properties properties, String prefix, WriterRetirementReview retained)
      throws IOException, InterruptedException {
    String kind = properties.getProperty(prefix + "retirement-kind", "systemd");
    if (kind.equals("systemd")) {
      return new WriterRetirementReview(
          new LocalServiceRetirement.Review(
              1,
              InstallationMain.required(properties, prefix + "machine-id"),
              names(properties, prefix + "retired-units")),
          null);
    }
    if (kind.equals("docker")) {
      var names = names(properties, prefix + "retired-containers");
      if (retained == null) {
        return new WriterRetirementReview(null, DockerContainerRetirement.capture(names));
      }
      if (retained.docker() == null
          || !retained.docker().containers().stream()
              .map(DockerContainerRetirement.Target::name)
              .toList()
              .equals(names)) {
        throw new IllegalArgumentException("Docker retirement inventory differs from review");
      }
      return retained;
    }
    throw new IllegalArgumentException("Unsupported writer retirement adapter");
  }

  String fingerprint() {
    return systemd == null ? docker.fingerprint() : systemd.fingerprint();
  }

  Runnable verifier(LocalServiceRetirement.Probe testProbe) {
    if (systemd != null) {
      return testProbe == null
          ? new LocalServiceRetirement(systemd)
          : new LocalServiceRetirement(systemd, testProbe);
    }
    if (testProbe != null) {
      throw new IllegalArgumentException("A service probe cannot replace Docker retirement");
    }
    return new DockerContainerRetirement(docker);
  }

  private static List<String> names(Properties properties, String key) {
    String raw = InstallationMain.required(properties, key);
    if (raw.length() > 16384) {
      throw new IllegalArgumentException("Writer retirement inventory exceeds its bound");
    }
    var names = Arrays.stream(raw.split(",", -1)).map(String::strip).sorted().toList();
    if (names.isEmpty() || names.size() > 64 || names.stream().distinct().count() != names.size()) {
      throw new IllegalArgumentException("Invalid writer retirement inventory");
    }
    return names;
  }
}
