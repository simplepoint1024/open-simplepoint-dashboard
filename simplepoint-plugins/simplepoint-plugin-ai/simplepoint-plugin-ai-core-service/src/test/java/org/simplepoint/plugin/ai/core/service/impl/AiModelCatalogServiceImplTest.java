package org.simplepoint.plugin.ai.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ModelSyncResult;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiProviderAdapterRegistry;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;

@ExtendWith(MockitoExtension.class)
class AiModelCatalogServiceImplTest {

  @Mock
  private AiProviderDefinitionRepository providerRepository;

  @Mock
  private AiModelDefinitionRepository modelRepository;

  private AiModelCatalogServiceImpl service;

  @BeforeEach
  void setUp() {
    AiProperties properties = new AiProperties();
    properties.setCredentialEncryptionKey("test-master-key");
    AiCredentialCipher cipher = new AiCredentialCipher(properties);
    AiProviderAdapter adapter = new StubAdapter();
    service = new AiModelCatalogServiceImpl(
        providerRepository,
        modelRepository,
        new AiProviderAdapterRegistry(List.of(adapter)),
        cipher,
        properties,
        mock(AiScopeAccessPolicy.class)
    );
    AiProviderDefinition provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setCode("openai");
    provider.setProviderType(AiProviderType.OPENAI);
    provider.setBaseUrl("https://api.openai.com/v1");
    provider.setEnabled(true);
    provider.setScopeType(org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM);
    provider.setCredentialCiphertext(cipher.encrypt("sk-test"));
    when(providerRepository.findActiveById("provider-1")).thenReturn(Optional.of(provider));
    when(providerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void shouldCreateDiscoveredModels() {
    when(modelRepository.findAllActiveByProviderId("provider-1")).thenReturn(List.of());
    when(modelRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ModelSyncResult result = service.syncModels("provider-1");

    assertEquals(2, result.discovered());
    assertEquals(2, result.created());
    verify(modelRepository).saveAll(any());
  }

  @Test
  void shouldMarkMissingDiscoveredModelUnavailableButKeepManualModel() {
    AiModelDefinition missing = model("old-model", true);
    AiModelDefinition manual = model("manual-model", false);
    when(modelRepository.findAllActiveByProviderId("provider-1"))
        .thenReturn(List.of(missing, manual));
    when(modelRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ModelSyncResult result = service.syncModels("provider-1");

    assertEquals(1, result.unavailable());
    assertFalse(missing.getAvailable());
    assertEquals(Boolean.TRUE, manual.getAvailable());
  }

  private static AiModelDefinition model(final String modelId, final boolean discovered) {
    AiModelDefinition model = new AiModelDefinition();
    model.setId(modelId);
    model.setProviderId("provider-1");
    model.setModelId(modelId);
    model.setModelType(AiModelType.LLM);
    model.setAvailable(true);
    model.setDiscovered(discovered);
    return model;
  }

  private static final class StubAdapter implements AiProviderAdapter {

    @Override
    public boolean supports(final AiProviderType providerType) {
      return providerType == AiProviderType.OPENAI;
    }

    @Override
    public List<DiscoveredModel> discoverModels(final ProviderConnection connection) {
      return List.of(
          new DiscoveredModel("gpt-test", "GPT Test", AiModelType.LLM, "test", null, "{}"),
          new DiscoveredModel(
              "text-embedding-test",
              "Embedding Test",
              AiModelType.EMBEDDING,
              "test",
              null,
              "{}"
          )
      );
    }
  }
}
