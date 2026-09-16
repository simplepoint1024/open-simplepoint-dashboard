package org.simplepoint.plugin.ai.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;

class AiRuntimeProfileCodecTest {

  private final AiRuntimeProfileCodec codec = new AiRuntimeProfileCodec(
      new ObjectMapper()
  );

  @Test
  void isolatesOpaqueWorkspaceReferencesByTenantScope() {
    RuntimeMcpProfileSpec profile = profileWithWorkspace(
        "shared-human-readable-reference"
    );

    String tenantA = codec.storage(
        profile, AiResourceScope.TENANT, "tenant-a"
    ).getFirst().source();
    String tenantB = codec.storage(
        profile, AiResourceScope.TENANT, "tenant-b"
    ).getFirst().source();

    assertThat(tenantA)
        .matches("open-simplepoint-managed-[a-f0-9]{40}")
        .doesNotContain("shared-human-readable-reference")
        .isNotEqualTo(tenantB);
  }

  @Test
  void neverTurnsEphemeralStorageIntoHostOrNamedVolumeMount() {
    RuntimeMcpProfileSpec profile = new RuntimeMcpProfileSpec(
        "io.example/ephemeral",
        "1.0.0",
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            RuntimeMcpProfileSpec.ArtifactType.OCI,
            "ghcr.io/example/ephemeral@sha256:" + "a".repeat(64)
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO, null, null
        ),
        null,
        List.of(),
        List.of(),
        List.of(new RuntimeMcpProfileSpec.StorageBinding(
            "output",
            RuntimeMcpProfileSpec.StorageType.EPHEMERAL,
            "opaque-ephemeral-id",
            "/output",
            false,
            16L * 1024 * 1024
        )),
        null,
        null,
        null
    );

    assertThat(codec.storage(
        profile, AiResourceScope.TENANT, "tenant-a"
    ).getFirst().source()).isNull();
  }

  private static RuntimeMcpProfileSpec profileWithWorkspace(
      final String storageReference
  ) {
    return new RuntimeMcpProfileSpec(
        "io.example/filesystem",
        "1.0.0",
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            RuntimeMcpProfileSpec.ArtifactType.OCI,
            "ghcr.io/example/filesystem@sha256:" + "b".repeat(64)
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO, null, null
        ),
        null,
        List.of(),
        List.of(),
        List.of(new RuntimeMcpProfileSpec.StorageBinding(
            "workspace",
            RuntimeMcpProfileSpec.StorageType.WORKSPACE_RW,
            storageReference,
            "/workspace",
            false,
            null
        )),
        null,
        null,
        new RuntimeMcpProfileSpec.SandboxPolicy(
            RuntimeMcpProfileSpec.SandboxProfile.WORKSPACE,
            Map.of()
        )
    );
  }
}
