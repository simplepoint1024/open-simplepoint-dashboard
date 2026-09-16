package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadSubmitRequest;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;

@ExtendWith(MockitoExtension.class)
class AiRuntimeWorkloadServiceImplTest {

  @Mock
  private AiRuntimeWorkloadRepository repository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private AiRuntimeSecretService secretService;

  @Mock
  private AiRuntimeEgressPolicyCodec egressPolicyCodec;

  @Mock
  private AiRuntimeImageResolver imageResolver;

  private AiRuntimeWorkloadServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AiRuntimeWorkloadServiceImpl(
        repository,
        new AiRuntimeProperties(),
        scopeAccessPolicy,
        secretService,
        egressPolicyCodec,
        imageResolver
    );
  }

  @Test
  void resolvesTagToDigestBeforeSubmittingWorkload() {
    String resolvedDigest = "sha256:" + "d".repeat(64);
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(repository.findActiveByExecutionIdForUpdate("execution-a"))
        .thenReturn(Optional.empty());
    when(imageResolver.resolve("ghcr.io/example/mcp:latest"))
        .thenReturn(new RuntimeImageResolution(resolvedDigest, "policy"));
    when(egressPolicyCodec.normalize("none", List.of())).thenReturn("[]");
    when(secretService.normalizeReferences(
        List.of(),
        AiResourceScope.SYSTEM,
        null
    )).thenReturn("[]");
    when(repository.save(any())).thenAnswer(invocation ->
        invocation.getArgument(0));

    AiRuntimeWorkload result = service.submit(new RuntimeWorkloadSubmitRequest(
        "execution-a",
        "server-a",
        "ghcr.io/example/mcp:latest",
        "",
        256L * 1024 * 1024,
        500_000_000L,
        128,
        300,
        "none",
        List.of(),
        List.of()
    ));

    assertThat(result.getImageReference())
        .isEqualTo("ghcr.io/example/mcp:latest");
    assertThat(result.getImageDigest()).isEqualTo(resolvedDigest);
    assertThat(result.getStatus()).isEqualTo(RuntimeWorkloadStatus.PENDING);
    verify(imageResolver).resolve("ghcr.io/example/mcp:latest");
  }
}
