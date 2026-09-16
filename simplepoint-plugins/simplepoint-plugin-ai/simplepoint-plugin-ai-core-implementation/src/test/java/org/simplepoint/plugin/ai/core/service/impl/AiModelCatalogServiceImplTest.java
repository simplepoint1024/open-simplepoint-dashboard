package org.simplepoint.plugin.ai.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderErrorCode;
import org.simplepoint.plugin.ai.core.api.model.AiProviderMessageCode;
import org.simplepoint.plugin.ai.core.api.model.AiProviderOperationException;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ConnectionTestResult;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredPricing;
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

  private AiProviderDefinition provider;

  private AiCredentialCipher cipher;

  private AiProperties properties;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    properties.setCredentialEncryptionKey("test-master-key");
    cipher = new AiCredentialCipher(properties);
    service = serviceWith(new StubAdapter());
    provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setCode("openai");
    provider.setProviderType(AiProviderType.OPENAI);
    provider.setBaseUrl("https://api.openai.com/v1");
    provider.setEnabled(true);
    provider.setScopeType(org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM);
    provider.setCredentialCiphertext(cipher.encrypt("sk-test"));
    lenient().when(providerRepository.findActiveById("provider-1"))
        .thenReturn(Optional.of(provider));
    lenient().when(providerRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void shouldCreateDiscoveredModels() {
    when(modelRepository.findAllActiveByProviderId("provider-1")).thenReturn(List.of());
    when(modelRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ModelSyncResult result = service.syncModels("provider-1");

    assertEquals(2, result.discovered());
    assertEquals(2, result.created());
    assertEquals(
        AiProviderMessageCode.MODEL_SYNC_SUCCEEDED.name(),
        provider.getLastMessage()
    );
    verify(modelRepository).saveAll(any());
  }

  @Test
  void connectionResultAndPersistedMessageUseStableCode() {
    ConnectionTestResult result = service.testConnection("provider-1");

    assertTrue(result.success());
    assertEquals(2, result.discoveredModelCount());
    assertEquals(
        AiProviderMessageCode.CONNECTION_TEST_SUCCEEDED,
        result.messageCode()
    );
    assertEquals(
        AiProviderMessageCode.CONNECTION_TEST_SUCCEEDED.name(),
        provider.getLastMessage()
    );
  }

  @Test
  void connectionFailurePersistsCodeWithoutAdapterProse() {
    service = serviceWith(new FailingAdapter());

    AiProviderOperationException failure = assertThrows(
        AiProviderOperationException.class,
        () -> service.testConnection("provider-1")
    );

    assertEquals(
        AiProviderErrorCode.AI_PROVIDER_CONNECTION_TEST_FAILED,
        failure.getErrorCode()
    );
    assertFalse(failure.getMessage().contains("provider-specific"));
    assertEquals("FAILED", provider.getLastStatus());
    assertEquals(
        AiProviderMessageCode.CONNECTION_TEST_FAILED.name(),
        provider.getLastMessage()
    );
    assertFalse(provider.getLastMessage().contains("provider-specific"));
  }

  @Test
  void synchronizationFailurePersistsCodeWithoutAdapterProse() {
    service = serviceWith(new FailingAdapter());

    AiProviderOperationException failure = assertThrows(
        AiProviderOperationException.class,
        () -> service.syncModels("provider-1")
    );

    assertEquals(
        AiProviderErrorCode.AI_PROVIDER_MODEL_SYNC_FAILED,
        failure.getErrorCode()
    );
    assertFalse(failure.getMessage().contains("provider-specific"));
    assertEquals("FAILED", provider.getLastStatus());
    assertEquals(
        AiProviderMessageCode.MODEL_SYNC_FAILED.name(),
        provider.getLastMessage()
    );
    assertFalse(provider.getLastMessage().contains("provider-specific"));
  }

  @Test
  void discoveryFailureUsesStableCodeWithoutAdapterProse() {
    service = serviceWith(new FailingAdapter());

    AiProviderOperationException failure = assertThrows(
        AiProviderOperationException.class,
        () -> service.discoverModels("provider-1")
    );

    assertEquals(
        AiProviderErrorCode.AI_PROVIDER_MODEL_DISCOVERY_FAILED,
        failure.getErrorCode()
    );
    assertFalse(failure.getMessage().contains("provider-specific"));
  }

  @Test
  void rateLimitFailureUsesActionableStableCode() {
    service = serviceWith(new RateLimitedAdapter());

    AiProviderOperationException failure = assertThrows(
        AiProviderOperationException.class,
        () -> service.discoverModels("provider-1")
    );

    assertEquals(AiProviderErrorCode.AI_PROVIDER_RATE_LIMITED, failure.getErrorCode());
  }

  @Test
  void everyOperationRejectsBlankProviderIdWithStableCode() {
    assertAllOperationsFailWith(
        "  ",
        AiProviderErrorCode.AI_PROVIDER_ID_REQUIRED
    );
  }

  @Test
  void everyOperationRejectsMissingProviderWithStableCode() {
    when(providerRepository.findActiveById("missing"))
        .thenReturn(Optional.empty());

    assertAllOperationsFailWith(
        "missing",
        AiProviderErrorCode.AI_PROVIDER_NOT_FOUND
    );
  }

  @Test
  void everyOperationRejectsDisabledProviderWithStableCode() {
    provider.setEnabled(false);

    assertAllOperationsFailWith(
        "provider-1",
        AiProviderErrorCode.AI_PROVIDER_DISABLED
    );
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

  @Test
  void unknownDiscoveryTypeRemainsOtherAndPricingIsDetected() {
    service = serviceWith(new MetadataAdapter());
    List<AiModelDefinition> saved = new java.util.ArrayList<>();
    when(modelRepository.findAllActiveByProviderId("provider-1")).thenReturn(List.of());
    when(modelRepository.saveAll(any())).thenAnswer(invocation -> {
      List<AiModelDefinition> models = invocation.getArgument(0);
      saved.addAll(models);
      return models;
    });

    service.syncModels("provider-1");

    AiModelDefinition model = saved.getFirst();
    assertEquals("opaque-model-id", model.getModelId());
    assertEquals(AiModelType.OTHER, model.getModelType());
    assertTrue(model.getPricingAutoDetected());
    assertTrue(model.getBillingEnabled());
    assertEquals(new BigDecimal("1.25000000"), model.getInputTokenPrice());
    assertEquals(new BigDecimal("5.00000000"), model.getOutputTokenPrice());
  }

  @Test
  void discoveryDoesNotOverwriteManualTypeOrPricing() {
    service = serviceWith(new MetadataAdapter());
    AiModelDefinition local = model("opaque-model-id", true);
    local.setModelType(AiModelType.IMAGE);
    local.setTypeAutoDetected(Boolean.FALSE);
    local.setPricingAutoDetected(Boolean.FALSE);
    local.setBillingEnabled(Boolean.TRUE);
    local.setBillingCurrency("CNY");
    local.setInputTokenPrice(new BigDecimal("9.00000000"));
    when(modelRepository.findAllActiveByProviderId("provider-1")).thenReturn(List.of(local));
    when(modelRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.syncModels("provider-1");

    assertEquals(AiModelType.IMAGE, local.getModelType());
    assertEquals("CNY", local.getBillingCurrency());
    assertEquals(new BigDecimal("9.00000000"), local.getInputTokenPrice());
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

  private AiModelCatalogServiceImpl serviceWith(
      final AiProviderAdapter adapter
  ) {
    return new AiModelCatalogServiceImpl(
        providerRepository,
        modelRepository,
        new AiProviderAdapterRegistry(List.of(adapter)),
        cipher,
        properties,
        mock(AiScopeAccessPolicy.class)
    );
  }

  private void assertAllOperationsFailWith(
      final String providerId,
      final AiProviderErrorCode expectedCode
  ) {
    List<Function<String, ?>> operations = List.of(
        service::testConnection,
        service::discoverModels,
        service::syncModels
    );
    for (Function<String, ?> operation : operations) {
      AiProviderOperationException failure = assertThrows(
          AiProviderOperationException.class,
          () -> operation.apply(providerId)
      );
      assertEquals(expectedCode, failure.getErrorCode());
      assertEquals(expectedCode.name(), failure.getMessage());
    }
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

  private static final class FailingAdapter implements AiProviderAdapter {

    @Override
    public boolean supports(final AiProviderType providerType) {
      return providerType == AiProviderType.OPENAI;
    }

    @Override
    public List<DiscoveredModel> discoverModels(
        final ProviderConnection connection
    ) {
      throw new IllegalStateException(
          "provider-specific environment prose must not be persisted"
      );
    }
  }

  private static final class MetadataAdapter implements AiProviderAdapter {

    @Override
    public boolean supports(final AiProviderType providerType) {
      return providerType == AiProviderType.OPENAI;
    }

    @Override
    public List<DiscoveredModel> discoverModels(final ProviderConnection connection) {
      return List.of(new DiscoveredModel(
          "opaque-model-id",
          "Opaque model",
          null,
          "test",
          null,
          "{}",
          new DiscoveredPricing(
              "USD",
              new BigDecimal("1.25000000"),
              null,
              new BigDecimal("5.00000000"),
              null
          )
      ));
    }
  }

  private static final class RateLimitedAdapter implements AiProviderAdapter {

    @Override
    public boolean supports(final AiProviderType providerType) {
      return providerType == AiProviderType.OPENAI;
    }

    @Override
    public List<DiscoveredModel> discoverModels(final ProviderConnection connection) {
      throw new AiProviderRequestException(429, "rate limited");
    }
  }
}
