package org.simplepoint.installation.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.simplepoint.platform.installation.InstallationFingerprint;

/**
 * Local systemd maintenance fence. Remote/manual executors require a separate retirement adapter.
 */
final class LocalServiceRetirement implements Runnable {
  private final Review review;
  private final Probe probe;

  LocalServiceRetirement(Review review) {
    this(review, new SystemdProbe());
  }

  LocalServiceRetirement(Review review, Probe probe) {
    this.review = Objects.requireNonNull(review, "review");
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  @Override
  public void run() {
    try {
      if (!review.machineId().equals(probe.machineId())) {
        throw rejected();
      }
      for (String unit : review.units()) {
        var state = probe.state(unit);
        if (!unit.equals(state.get("Id"))
            || !"masked".equals(state.get("LoadState"))
            || !List.of("masked", "masked-runtime")
                .contains(Objects.toString(state.get("UnitFileState"), ""))
            || !List.of("inactive", "failed")
                .contains(Objects.toString(state.get("ActiveState"), ""))
            || !"0".equals(state.get("MainPID"))) {
          throw rejected();
        }
        String cgroup = state.get("ControlGroup");
        if (cgroup == null || !cgroup.isEmpty() && probe.populated(cgroup)) {
          throw rejected();
        }
      }
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw rejected();
    }
  }

  /**
   * Explicit complete old-writer unit inventory from the reviewed deployment, not process
   * discovery.
   */
  record Review(int version, String machineId, List<String> units) {
    Review {
      if (version != 1
          || machineId == null
          || !machineId.matches("[0-9a-f]{32}")
          || units == null
          || units.isEmpty()
          || units.size() > 64
          || units.stream().distinct().count() != units.size()
          || units.stream()
              .anyMatch(
                  u -> u == null || !u.matches("[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,120}\\.service"))) {
        throw new IllegalArgumentException("Invalid local writer retirement review");
      }
      units = units.stream().sorted().toList();
    }

    String fingerprint() {
      var values = new java.util.ArrayList<String>();
      values.add("local-systemd-writer-retirement-v1");
      values.add(machineId);
      values.addAll(units);
      return InstallationFingerprint.of(values.toArray(String[]::new));
    }
  }

  interface Probe {
    String machineId() throws IOException;

    Map<String, String> state(String unit) throws IOException, InterruptedException;

    boolean populated(String cgroup) throws IOException;
  }

  private static final class SystemdProbe implements Probe {
    @Override
    public String machineId() throws IOException {
      return bounded(Path.of("/etc/machine-id"), 128).strip();
    }

    @Override
    public Map<String, String> state(String unit) throws IOException, InterruptedException {
      // No shell, environment-provided executable or caller-defined command is executed.
      Path output = Files.createTempFile("simplepoint-retirement-", ".state");
      Process process = null;
      try {
        process =
            new ProcessBuilder(
                    "/usr/bin/systemctl",
                    "show",
                    "--no-pager",
                    "--property=Id,LoadState,ActiveState,UnitFileState,MainPID,ControlGroup",
                    "--",
                    unit)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)
            || process.exitValue() != 0) {
          throw rejected();
        }
        var result = new HashMap<String, String>();
        for (String line : bounded(output, 16384).split("\n")) {
          int separator = line.indexOf('=');
          if (separator <= 0
              || result.putIfAbsent(line.substring(0, separator), line.substring(separator + 1))
                  != null) {
            throw rejected();
          }
        }
        return Map.copyOf(result);
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(1, TimeUnit.SECONDS);
        }
        Files.deleteIfExists(output);
      }
    }

    @Override
    public boolean populated(String cgroup) throws IOException {
      Path root = Path.of("/sys/fs/cgroup");
      if (!cgroup.startsWith("/") || cgroup.equals("/")) {
        throw rejected();
      }
      Path directory = root.resolve(cgroup.substring(1)).normalize();
      if (!directory.startsWith(root) || directory.equals(root)) {
        throw rejected();
      }
      // Missing/inaccessible cgroups fail closed instead of assuming a process has exited.
      String events = bounded(directory.resolve("cgroup.events"), 4096);
      return !events.lines().anyMatch(line -> line.equals("populated 0"));
    }
  }

  private static String bounded(Path path, int limit) throws IOException {
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) {
        throw rejected();
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Local writer retirement is not established");
  }
}
