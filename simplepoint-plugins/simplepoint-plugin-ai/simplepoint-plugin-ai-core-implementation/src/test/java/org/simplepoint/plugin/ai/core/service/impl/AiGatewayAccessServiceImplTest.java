package org.simplepoint.plugin.ai.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiApiKeyRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.service.security.AiApiKeyHasher;

class AiGatewayAccessServiceImplTest {

  private final AiApiKeyRepository apiKeyRepository = mock(AiApiKeyRepository.class);

  private final AiModelDefinitionRepository modelRepository = mock(AiModelDefinitionRepository.class);

  private AiApiKeyHasher keyHasher;

  private AiGatewayAccessServiceImpl service;

  @BeforeEach
  void setUp() {
    AiProperties properties = new AiProperties();
    properties.setApiKeyHashPepper("unit-test-api-key-pepper");
    properties.setApiKeyDefaultRateLimitPerMinute(10);
    keyHasher = new AiApiKeyHasher(properties);
    service = new AiGatewayAccessServiceImpl(
        apiKeyRepository, modelRepository, keyHasher, properties);
  }

  @Test
  void authenticatesActiveKeyAndRecordsUsage() {
    AiApiKeyHasher.IssuedSecret issued = keyHasher.issue();
    AiApiKey key = key("key-1", issued, AiResourceScope.TENANT, "tenant-1");
    when(apiKeyRepository.findActiveByPrefix(issued.prefix())).thenReturn(Optional.of(key));

    GatewaySession session = service.authenticate(issued.rawKey(), "127.0.0.1");

    assertThat(session.apiKeyId()).isEqualTo("key-1");
    assertThat(session.tenantId()).isEqualTo("tenant-1");
    verify(apiKeyRepository).recordUsage(any(), any(Instant.class));
  }

  @Test
  void rejectsExpiredKey() {
    AiApiKeyHasher.IssuedSecret issued = keyHasher.issue();
    AiApiKey key = key("key-1", issued, AiResourceScope.SYSTEM, null);
    key.setExpiresAt(Instant.now().minusSeconds(1));
    when(apiKeyRepository.findActiveByPrefix(issued.prefix())).thenReturn(Optional.of(key));

    assertThatThrownBy(() -> service.authenticate(issued.rawKey(), "127.0.0.1"))
        .isInstanceOf(AiGatewayAccessException.class)
        .hasMessageContaining("过期");
  }

  @Test
  void exposesOnlyGenerationModelsAndDisambiguatesDuplicateProviderIds() {
    GatewaySession session = new GatewaySession(
        "key-1", "integration", AiResourceScope.TENANT, "tenant-1");
    AiModelDefinition first = model("model-1", "shared-model", AiModelType.LLM);
    AiModelDefinition second = model("model-2", "shared-model", AiModelType.MULTIMODAL);
    AiModelDefinition embedding = model("model-3", "embed-model", AiModelType.EMBEDDING);
    when(modelRepository.findAllAvailableForTenant("tenant-1"))
        .thenReturn(List.of(first, second, embedding));

    var available = service.availableModels(session);

    assertThat(available).extracting(model -> model.id())
        .containsExactly("model-1", "model-2");
    assertThat(service.resolveModelDefinitionId(session, "model-2")).isEqualTo("model-2");
  }

  private static AiApiKey key(
      final String id,
      final AiApiKeyHasher.IssuedSecret issued,
      final AiResourceScope scope,
      final String tenantId
  ) {
    AiApiKey key = new AiApiKey();
    key.setId(id);
    key.setName("integration");
    key.setScopeType(scope);
    key.setTenantId(tenantId);
    key.setKeyPrefix(issued.prefix());
    key.setSecretHash(issued.hash());
    key.setEnabled(true);
    key.setUsageCount(0L);
    return key;
  }

  private static AiModelDefinition model(
      final String id,
      final String modelId,
      final AiModelType modelType
  ) {
    AiModelDefinition model = new AiModelDefinition();
    model.setId(id);
    model.setModelId(modelId);
    model.setDisplayName(modelId);
    model.setModelType(modelType);
    model.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return model;
  }
}
