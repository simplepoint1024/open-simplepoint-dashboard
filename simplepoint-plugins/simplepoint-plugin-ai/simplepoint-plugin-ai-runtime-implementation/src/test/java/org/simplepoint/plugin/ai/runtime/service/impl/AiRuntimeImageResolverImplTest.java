package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.springframework.data.domain.PageImpl;

class AiRuntimeImageResolverImplTest {

  @Test
  void pullsTagAndReturnsItsObservedRepositoryDigest() {
    final AiRuntimeNodeRepository nodes = mock(AiRuntimeNodeRepository.class);
    final AiRuntimeNodeOperations operations =
        mock(AiRuntimeNodeOperations.class);
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId("node-a");
    node.setAdvertiseUrl("https://runtime-a:2891");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(60));
    String digest = "sha256:" + "e".repeat(64);
    when(nodes.findAllActive(any())).thenReturn(new PageImpl<>(List.of(node)));
    when(operations.prepare(
        "https://runtime-a:2891",
        "ghcr.io/example/mcp:latest"
    )).thenReturn(new RuntimeImageObservation(
        "ghcr.io/example/mcp:latest",
        "sha256:" + "f".repeat(64),
        List.of("ghcr.io/example/mcp@" + digest),
        Map.of(),
        "stdio",
        null,
        true,
        false,
        false,
        "policy-a"
    ));
    AiRuntimeImageResolverImpl resolver = new AiRuntimeImageResolverImpl(
        nodes,
        operations
    );

    RuntimeImageResolution result = resolver.resolve(
        "ghcr.io/example/mcp:latest"
    );

    assertThat(result.imageDigest()).isEqualTo(digest);
    assertThat(result.admissionPolicyHash()).isEqualTo("policy-a");
    verify(operations).prepare(
        "https://runtime-a:2891",
        "ghcr.io/example/mcp:latest"
    );
  }
}
