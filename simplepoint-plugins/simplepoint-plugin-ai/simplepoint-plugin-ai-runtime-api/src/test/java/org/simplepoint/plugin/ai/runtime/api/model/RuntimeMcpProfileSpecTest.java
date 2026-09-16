package org.simplepoint.plugin.ai.runtime.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.Artifact;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.ArtifactSource;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.ArtifactType;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.NetworkMode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.NetworkPolicy;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.ProcessUserMode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SandboxPolicy;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SandboxProfile;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SecretBinding;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SecretTarget;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SessionMode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.SessionPolicy;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.StorageBinding;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.StorageType;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.Transport;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec.TransportType;

class RuntimeMcpProfileSpecTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void describesUnmodifiedGithubImageWithExecTimeOauthToken() {
    RuntimeMcpProfileSpec profile = new RuntimeMcpProfileSpec(
        "io.github.github/github-mcp-server",
        "1.2.3",
        new Artifact(
            ArtifactSource.UPSTREAM_ORIGINAL,
            ArtifactType.OCI,
            "ghcr.io/github/github-mcp-server:1.2.3"
        ),
        new Transport(TransportType.STDIO, null, null),
        new RuntimeMcpProfileSpec.Process(
            List.of(),
            List.of("/server"),
            List.of("stdio"),
            null
        ),
        List.of(),
        List.of(new SecretBinding(
            "github-oauth",
            "secret://github/user-1/access-token",
            SecretTarget.ENV_AT_EXEC,
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        )),
        List.of(),
        new NetworkPolicy(NetworkMode.HTTP_EGRESS, List.of("api.github.com:443")),
        new SessionPolicy(SessionMode.DEDICATED, 1),
        new SandboxPolicy(SandboxProfile.STRICT, Map.of())
    );

    assertThat(profile.artifact().source())
        .isEqualTo(ArtifactSource.UPSTREAM_ORIGINAL);
    assertThat(profile.secrets()).singleElement()
        .satisfies(binding -> assertThat(binding.target())
            .isEqualTo(SecretTarget.ENV_AT_EXEC));
  }

  @Test
  void rejectsShellCommandAndSecretOutsideBrokerDirectory() {
    assertThatThrownBy(() -> new RuntimeMcpProfileSpec.Process(
        List.of(),
        List.of("/bin/sh"),
        List.of("-c", "download-and-run"),
        null
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Shell -c");

    assertThatThrownBy(() -> new RuntimeMcpProfileSpec.Process(
        List.of(),
        List.of(),
        List.of(),
        null,
        ProcessUserMode.RUNTIME_DEFAULT,
        List.of("sh", "-c", "git init")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Shell -c");

    assertThatThrownBy(() -> new SecretBinding(
        "token",
        "secret://token",
        SecretTarget.FILE,
        "/tmp/token"
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/run/secrets/simplepoint");

    assertThatThrownBy(() -> new SecretBinding(
        "provider-token",
        "provider://github/arbitrary-secret",
        SecretTarget.ENV_AT_EXEC,
        "GITHUB_TOKEN"
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Provider secret reference");
  }

  @Test
  void defaultsToRuntimeUserButCanPreserveAnOriginalImageUser() {
    var defaultProcess = new RuntimeMcpProfileSpec.Process(
        List.of(), List.of(), List.of(), null
    );
    var originalImageProcess = new RuntimeMcpProfileSpec.Process(
        List.of(), List.of(), List.of(), null, ProcessUserMode.IMAGE_DEFAULT
    );

    assertThat(defaultProcess.userMode()).isEqualTo(
        ProcessUserMode.RUNTIME_DEFAULT
    );
    assertThat(originalImageProcess.userMode()).isEqualTo(
        ProcessUserMode.IMAGE_DEFAULT
    );
  }

  @Test
  void rejectsAmbiguousOrEscapingContainerTransportPaths() {
    for (String path : List.of(
        "mcp",
        "//mcp",
        "/../mcp",
        "/%2e%2e/mcp",
        "/mcp?token=secret",
        "/mcp#fragment",
        "/mcp\\child",
        "/mcp\nchild"
    )) {
      assertThatThrownBy(() -> new Transport(
          TransportType.STREAMABLE_HTTP,
          8080,
          path
      )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("transport path");
    }
    assertThat(new Transport(
        TransportType.STREAMABLE_HTTP,
        8080,
        "/mcp/v1"
    ).path()).isEqualTo("/mcp/v1");
  }

  @Test
  void deploymentRevisionRequiresDigestPinnedOciArtifact() {
    RuntimeMcpProfileSpec profile = minimalOciProfile();

    RuntimeMcpDeploymentRevisionSpec revision =
        new RuntimeMcpDeploymentRevisionSpec(
            "revision-1",
            HASH,
            profile,
            HASH,
            "sha256:" + "b".repeat(64),
            HASH,
            Instant.parse("2026-08-05T00:00:00Z")
        );

    assertThat(revision.imageDigest()).startsWith("sha256:");
    assertThatThrownBy(() -> new RuntimeMcpDeploymentRevisionSpec(
        "revision-2",
        HASH,
        profile,
        HASH,
        "latest",
        null,
        Instant.now()
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 pinned");
  }

  @Test
  void defaultsToNoNetworkDedicatedSessionAndStrictSandbox() {
    RuntimeMcpProfileSpec profile = new RuntimeMcpProfileSpec(
        "io.github.example/server",
        "1.0.0",
        new Artifact(
            ArtifactSource.CATALOG_ORIGINAL,
            ArtifactType.OCI,
            "mcp/example:1.0.0"
        ),
        new Transport(TransportType.STDIO, null, null),
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );

    assertThat(profile.network().mode()).isEqualTo(NetworkMode.NONE);
    assertThat(profile.session())
        .isEqualTo(new SessionPolicy(SessionMode.DEDICATED, 1));
    assertThat(profile.sandbox().profile()).isEqualTo(SandboxProfile.STRICT);
  }

  @Test
  void browserAndWorkspaceProfilesRequireManagedStorage() {
    assertThatThrownBy(() -> profileWithStorage(
        List.of(),
        SandboxProfile.BROWSER
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/dev/shm");
    RuntimeMcpProfileSpec browser = profileWithStorage(List.of(
        new StorageBinding(
            "browser-shm",
            StorageType.TMPFS,
            null,
            "/dev/shm",
            false,
            64L * 1024 * 1024
        )
    ), SandboxProfile.BROWSER);
    assertThat(browser.storage()).singleElement()
        .extracting(StorageBinding::targetPath)
        .isEqualTo("/dev/shm");

    assertThatThrownBy(() -> new StorageBinding(
        "workspace",
        StorageType.WORKSPACE_RO,
        "workspace-1",
        "/workspace",
        false,
        null
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Read-only");
  }

  @Test
  void validatesNetworkTargetsByPolicyClass() {
    assertThat(new NetworkPolicy(
        NetworkMode.INTERNAL_SERVICE,
        List.of("postgres:5432")
    ).allowlist()).containsExactly("postgres:5432");
    assertThatThrownBy(() -> new NetworkPolicy(
        NetworkMode.TCP_EGRESS,
        List.of("db.example.com")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host:port");
    assertThatThrownBy(() -> new NetworkPolicy(
        NetworkMode.HTTP_EGRESS,
        List.of("api.example.com:8443")
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("80 or 443");
  }

  @Test
  void dataProfileRequiresExactTcpPolicy() {
    assertThatThrownBy(() -> profileWithStorage(
        List.of(), SandboxProfile.DATA
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Data sandbox");
  }

  private static RuntimeMcpProfileSpec profileWithStorage(
      final List<StorageBinding> storage,
      final SandboxProfile sandbox
  ) {
    return new RuntimeMcpProfileSpec(
        "io.example/storage-server",
        "1.0.0",
        new Artifact(
            ArtifactSource.UPSTREAM_ORIGINAL,
            ArtifactType.OCI,
            "ghcr.io/example/storage-server:1"
        ),
        new Transport(TransportType.STDIO, null, null),
        null,
        List.of(),
        List.of(),
        storage,
        new NetworkPolicy(NetworkMode.NONE, List.of()),
        new SessionPolicy(SessionMode.DEDICATED, 1),
        new SandboxPolicy(sandbox, Map.of())
    );
  }

  private static RuntimeMcpProfileSpec minimalOciProfile() {
    return new RuntimeMcpProfileSpec(
        "io.github.example/server",
        "1.0.0",
        new Artifact(
            ArtifactSource.UPSTREAM_ORIGINAL,
            ArtifactType.OCI,
            "ghcr.io/example/server:1.0.0"
        ),
        new Transport(TransportType.STDIO, null, null),
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );
  }
}
