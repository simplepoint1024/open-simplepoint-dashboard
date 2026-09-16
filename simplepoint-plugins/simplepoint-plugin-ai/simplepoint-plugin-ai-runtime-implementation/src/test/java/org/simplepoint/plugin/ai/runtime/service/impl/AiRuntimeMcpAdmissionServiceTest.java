package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProbeReport;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadDispatchRequest;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.springframework.data.domain.PageImpl;

class AiRuntimeMcpAdmissionServiceTest {

  @Test
  void probesLegacyPoolAsStdioBeforeDeployment() {
    final AiRuntimeNodeRepository nodes = mock(AiRuntimeNodeRepository.class);
    final AiRuntimeNodeOperations operations =
        mock(AiRuntimeNodeOperations.class);
    final AiRuntimeSecretService secrets = mock(AiRuntimeSecretService.class);
    final ObjectMapper mapper = new ObjectMapper();
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId("node-1");
    node.setAdvertiseUrl("https://runtime-1:2891");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(60));
    node.setMaxWorkloadMemoryBytes(1024L * 1024 * 1024);
    node.setMaxWorkloadNanoCpus(2_000_000_000L);
    node.setMaxWorkloadPidsLimit(512);
    when(nodes.findAllActive(any())).thenReturn(new PageImpl<>(List.of(node)));
    when(secrets.resolve(any(), any(), any())).thenReturn(List.of());
    RuntimeMcpProbeReport report = new RuntimeMcpProbeReport(
        "2025-11-25", true, 2, false, 0, false, 0
    );
    when(operations.probe(any(), any())).thenReturn(report);
    final AiRuntimeMcpAdmissionService service =
        new AiRuntimeMcpAdmissionService(
            nodes,
            operations,
            secrets,
            new AiRuntimeProfileCodec(mapper),
            new AiRuntimeEgressPolicyCodec(mapper),
            new AiRuntimeProperties(),
            mapper
        );
    AiRuntimePool pool = new AiRuntimePool();
    pool.setScopeType(AiResourceScope.SYSTEM);
    pool.setImageReference("ghcr.io/example/server:1");
    pool.setImageDigest("sha256:" + "a".repeat(64));
    pool.setRequestedMemoryBytes(256L * 1024 * 1024);
    pool.setRequestedNanoCpus(500_000_000L);
    pool.setRequestedPidsLimit(128);
    pool.setNetworkMode("none");
    pool.setEgressAllowlistJson("[]");
    pool.setSecretReferencesJson("[]");

    assertThat(service.admitLegacy(pool)).isSameAs(report);

    ArgumentCaptor<RuntimeWorkloadDispatchRequest> request =
        ArgumentCaptor.forClass(RuntimeWorkloadDispatchRequest.class);
    org.mockito.Mockito.verify(operations).probe(
        org.mockito.ArgumentMatchers.eq("https://runtime-1:2891"),
        request.capture()
    );
    assertThat(request.getValue().transport()).isEqualTo("stdio");
    assertThat(request.getValue().image()).isEqualTo(
        "ghcr.io/example/server:1@sha256:" + "a".repeat(64)
    );
    assertThat(request.getValue().memoryBytes())
        .isEqualTo(256L * 1024 * 1024);
  }

  @Test
  void probesAnImmutableProfileAndProducesSanitizedEvidence() {
    final AiRuntimeNodeRepository nodes = mock(AiRuntimeNodeRepository.class);
    final AiRuntimeNodeOperations operations =
        mock(AiRuntimeNodeOperations.class);
    final AiRuntimeSecretService secrets =
        mock(AiRuntimeSecretService.class);
    final ObjectMapper mapper = new ObjectMapper();
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId("node-1");
    node.setAdvertiseUrl("https://runtime-1:2891");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(60));
    when(nodes.findAllActive(any())).thenReturn(new PageImpl<>(List.of(node)));
    when(secrets.resolveBindings(any(), any(), any())).thenReturn(List.of());
    when(operations.probe(any(), any())).thenReturn(new RuntimeMcpProbeReport(
        "2025-11-25", true, 3, false, 0, false, 0
    ));
    AiRuntimeMcpAdmissionService service = new AiRuntimeMcpAdmissionService(
        nodes,
        operations,
        secrets,
        new AiRuntimeProfileCodec(mapper),
        new AiRuntimeEgressPolicyCodec(mapper),
        new AiRuntimeProperties(),
        mapper
    );
    AiRuntimeMcpProfile profile = new AiRuntimeMcpProfile();
    profile.setScopeType(AiResourceScope.SYSTEM);
    RuntimeMcpProfileSpec spec = spec();

    AiRuntimeMcpAdmissionService.AdmissionResult result = service.admit(
        profile,
        spec,
        "ghcr.io/example/server:1",
        "sha256:" + "a".repeat(64),
        "image-policy"
    );

    ArgumentCaptor<RuntimeWorkloadDispatchRequest> request =
        ArgumentCaptor.forClass(RuntimeWorkloadDispatchRequest.class);
    org.mockito.Mockito.verify(operations).probe(
        org.mockito.ArgumentMatchers.eq("https://runtime-1:2891"),
        request.capture()
    );
    assertThat(request.getValue().image()).isEqualTo(
        "ghcr.io/example/server:1@sha256:" + "a".repeat(64)
    );
    assertThat(request.getValue().transport()).isEqualTo("stdio");
    assertThat(request.getValue().arguments()).containsExactly("stdio");
    assertThat(request.getValue().secrets()).singleElement().satisfies(secret -> {
      assertThat(secret.name()).isEqualTo("provider-binding-0");
      assertThat(secret.value()).isEqualTo("simplepoint-admission-placeholder");
      assertThat(secret.targetEnvironment())
          .isEqualTo("GITHUB_PERSONAL_ACCESS_TOKEN");
    });
    assertThat(result.hash()).matches("[0-9a-f]{64}");
    assertThat(result.reportJson()).contains(
        "\"protocolVersion\":\"2025-11-25\"",
        "\"toolCount\":3",
        "\"imageAdmissionPolicyHash\":\"image-policy\""
    );
    assertThat(result.reportJson()).doesNotContain("environment", "secret");
  }

  private static RuntimeMcpProfileSpec spec() {
    return new RuntimeMcpProfileSpec(
        "io.example/server",
        "1.0.0",
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            RuntimeMcpProfileSpec.ArtifactType.OCI,
            "ghcr.io/example/server:1"
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO, null, null
        ),
        new RuntimeMcpProfileSpec.Process(
            List.of(), List.of(), List.of("stdio"), null
        ),
        List.of(),
        List.of(new RuntimeMcpProfileSpec.SecretBinding(
            "github-token",
            "provider://github/access-token",
            RuntimeMcpProfileSpec.SecretTarget.ENV_AT_EXEC,
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        )),
        List.of(),
        new RuntimeMcpProfileSpec.NetworkPolicy(
            RuntimeMcpProfileSpec.NetworkMode.NONE, List.of()
        ),
        new RuntimeMcpProfileSpec.SessionPolicy(
            RuntimeMcpProfileSpec.SessionMode.DEDICATED, 1
        ),
        new RuntimeMcpProfileSpec.SandboxPolicy(
            RuntimeMcpProfileSpec.SandboxProfile.STRICT, Map.of()
        )
    );
  }
}
