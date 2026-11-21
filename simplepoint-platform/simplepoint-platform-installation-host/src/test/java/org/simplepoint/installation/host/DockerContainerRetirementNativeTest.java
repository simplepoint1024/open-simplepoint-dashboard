package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Disposable sleeping containers only; never stops or updates existing application containers. */
class DockerContainerRetirementNativeTest {
  @Test
  void actualDockerRefusesRunningRestartableAndSameNameReplacementContainers() throws Exception {
    String image = System.getenv("PHYSICAL_OWNERSHIP_TEST_DOCKER_IMAGE");
    Assumptions.assumeTrue(image != null, "Explicit cached disposable image required");
    String name = "sp-retirement-test-" + UUID.randomUUID().toString().replace("-", "");
    String renamed = name + "-old";
    try {
      docker(
          "create",
          "--name",
          name,
          "--restart",
          "always",
          "--network",
          "none",
          "--read-only",
          "--entrypoint",
          "/bin/sh",
          image,
          "-c",
          "sleep 300");
      docker("start", name);
      var properties = new java.util.Properties();
      properties.setProperty("test.retirement-kind", "docker");
      properties.setProperty("test.retired-containers", name);
      var review = WriterRetirementReview.configured(properties, "test.", null);
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var saved = json.readValue(json.writeValueAsBytes(review), WriterRetirementReview.class);
      var check = WriterRetirementReview.configured(properties, "test.", saved).verifier(null);
      assertThatThrownBy(check::run).hasMessageContaining("not established");
      docker("stop", "--time", "1", name);
      assertThatThrownBy(check::run).hasMessageContaining("not established");
      docker("update", "--restart", "no", name);
      assertThatCode(check::run).doesNotThrowAnyException();
      docker("rename", name, renamed);
      docker(
          "create",
          "--name",
          name,
          "--restart",
          "no",
          "--network",
          "none",
          "--entrypoint",
          "/bin/sh",
          image,
          "-c",
          "exit 0");
      docker("start", "--attach", name);
      assertThatThrownBy(check::run).hasMessageContaining("not established");
      properties.setProperty("test.retired-containers", renamed);
      assertThatThrownBy(() -> WriterRetirementReview.configured(properties, "test.", saved))
          .hasMessageContaining("differs from review");
    } finally {
      docker("rm", "--force", name);
      docker("rm", "--force", renamed);
    }
  }

  private static void docker(String... arguments) throws Exception {
    var command =
        new java.util.ArrayList<>(
            List.of("/usr/bin/docker", "--host", "unix:///var/run/docker.sock"));
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Disposable Docker operation timed out");
    }
    // Cleanup tolerates an already-removed test container, but fixture setup must succeed.
    if (process.exitValue() != 0 && !arguments[0].equals("rm")) {
      throw new IllegalStateException("Disposable Docker operation failed");
    }
  }
}
