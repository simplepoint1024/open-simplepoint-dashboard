package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerContainerRetirementTest {
  @Test
  void daemonContainerImageAndRestartStateMustMatchTheReviewedStoppedExecutor() throws Exception {
    var current = new AtomicReference<>(state("exited", false, "no", 0));
    var daemon = new AtomicReference<>("daemon");
    var probe =
        new DockerContainerRetirement.Probe() {
          @Override
          public String daemonIdentity() {
            return daemon.get();
          }

          @Override
          public DockerContainerRetirement.State state(String name) {
            return current.get();
          }
        };
    var review = DockerContainerRetirement.capture(List.of("dna"), probe);
    var check = new DockerContainerRetirement(review, probe);
    assertThatCode(check::run).doesNotThrowAnyException();
    for (var state :
        List.of(
            state("running", true, "no", 100),
            state("exited", false, "always", 0),
            state("dead", false, "no", 0),
            state("exited", false, "no", 100),
            new DockerContainerRetirement.State(
                "c".repeat(64),
                "/dna",
                "sha256:" + "b".repeat(64),
                "exited",
                false,
                false,
                false,
                0,
                "no"),
            new DockerContainerRetirement.State(
                "a".repeat(64),
                "/dna",
                "sha256:" + "c".repeat(64),
                "exited",
                false,
                false,
                false,
                0,
                "no"))) {
      current.set(state);
      assertThatThrownBy(check::run).hasMessageContaining("not established");
    }
    current.set(state("exited", false, "no", 0));
    daemon.set("another");
    assertThatThrownBy(check::run).hasMessageContaining("not established");
  }

  private static DockerContainerRetirement.State state(
      String state, boolean running, String restart, long pid) {
    return new DockerContainerRetirement.State(
        "a".repeat(64),
        "/dna",
        "sha256:" + "b".repeat(64),
        state,
        running,
        false,
        false,
        pid,
        restart);
  }
}
