package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.simplepoint.platform.installation.InstallationFingerprint;

/**
 * Exact local Docker executors with restart disabled; never treats discovery removal as retirement.
 */
final class DockerContainerRetirement implements Runnable {
  private final Review review;
  private final Probe probe;

  DockerContainerRetirement(Review review) {
    this(review, new DockerProbe());
  }

  DockerContainerRetirement(Review review, Probe probe) {
    this.review = Objects.requireNonNull(review, "review");
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  static Review capture(List<String> names) throws IOException, InterruptedException {
    return capture(names, new DockerProbe());
  }

  static Review capture(List<String> names, Probe probe) throws IOException, InterruptedException {
    if (names == null || names.isEmpty() || names.size() > 64) {
      throw rejected();
    }
    var targets = new java.util.ArrayList<Target>();
    String daemon = probe.daemonIdentity();
    for (String name : names) {
      name(name);
      var state = probe.state(name);
      if (!("/" + name).equals(state.name())) {
        throw rejected();
      }
      targets.add(new Target(name, state.id(), state.image()));
    }
    if (!daemon.equals(probe.daemonIdentity())) {
      throw rejected();
    }
    return new Review(1, daemon, targets);
  }

  @Override
  public void run() {
    try {
      if (!review.daemonIdentity().equals(probe.daemonIdentity())) {
        throw rejected();
      }
      for (var target : review.containers()) {
        var state = probe.state(target.name());
        if (!target.id().equals(state.id())
            || !("/" + target.name()).equals(state.name())
            || !target.image().equals(state.image())
            || !"exited".equals(state.status())
            || state.running()
            || state.restarting()
            || state.paused()
            || state.pid() != 0
            || !"no".equals(state.restartPolicy())) {
          throw rejected();
        }
      }
      if (!review.daemonIdentity().equals(probe.daemonIdentity())) {
        throw rejected();
      }
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw rejected();
    }
  }

  record Review(int version, String daemonIdentity, List<Target> containers) {
    Review {
      if (version != 1
          || daemonIdentity == null
          || !daemonIdentity.matches("[a-zA-Z0-9:._-]{1,128}")
          || containers == null
          || containers.isEmpty()
          || containers.size() > 64) {
        throw rejected();
      }
      var names = new HashSet<String>();
      var ids = new HashSet<String>();
      for (var target : containers) {
        if (!names.add(target.name()) || !ids.add(target.id())) {
          throw rejected();
        }
      }
      containers = containers.stream().sorted(Comparator.comparing(Target::name)).toList();
    }

    String fingerprint() {
      var values = new java.util.ArrayList<String>();
      values.add("local-docker-writer-retirement-v1");
      values.add(daemonIdentity);
      for (var target : containers) {
        values.add(target.name());
        values.add(target.id());
        values.add(target.image());
      }
      return InstallationFingerprint.of(values.toArray(String[]::new));
    }
  }

  record Target(String name, String id, String image) {
    Target {
      DockerContainerRetirement.name(name);
      if (id == null
          || !id.matches("[0-9a-f]{64}")
          || image == null
          || !image.matches("sha256:[0-9a-f]{64}")) {
        throw rejected();
      }
    }
  }

  record State(
      String id,
      String name,
      String image,
      String status,
      boolean running,
      boolean restarting,
      boolean paused,
      long pid,
      String restartPolicy) {}

  interface Probe {
    String daemonIdentity() throws IOException, InterruptedException;

    State state(String name) throws IOException, InterruptedException;
  }

  private static final class DockerProbe implements Probe {
    private static final ObjectMapper JSON =
        new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String FORMAT =
        "{\"id\":{{json .Id}},\"name\":{{json .Name}},"
            + "\"image\":{{json .Image}},\"status\":{{json .State.Status}},"
            + "\"running\":{{json .State.Running}},\"restarting\":{{json .State.Restarting}},"
            + "\"paused\":{{json .State.Paused}},\"pid\":{{json .State.Pid}},"
            + "\"restartPolicy\":{{json .HostConfig.RestartPolicy.Name}}}";

    @Override
    public String daemonIdentity() throws IOException, InterruptedException {
      return JSON.readValue(command(List.of("info", "--format", "{{json .ID}}")), String.class);
    }

    @Override
    public State state(String name) throws IOException, InterruptedException {
      DockerContainerRetirement.name(name);
      return JSON.readValue(
          command(List.of("inspect", "--type", "container", "--format", FORMAT, "--", name)),
          State.class);
    }

    private static byte[] command(List<String> arguments) throws IOException, InterruptedException {
      var command =
          new java.util.ArrayList<>(
              List.of("/usr/bin/docker", "--host", "unix:///var/run/docker.sock"));
      command.addAll(arguments);
      Path output = Files.createTempFile("simplepoint-retirement-", ".docker");
      Process process = null;
      try {
        // Only selected identity/state fields are requested; never inspect environment/secrets.
        process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
          throw rejected();
        }
        try (var input = Files.newInputStream(output)) {
          byte[] bytes = input.readNBytes(16385);
          if (bytes.length > 16384) {
            throw rejected();
          }
          return bytes;
        }
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(1, TimeUnit.SECONDS);
        }
        Files.deleteIfExists(output);
      }
    }
  }

  private static void name(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
      throw rejected();
    }
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Docker writer retirement is not established");
  }
}
