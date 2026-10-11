package org.simplepoint.ai.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.*;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderOption;
import org.simplepoint.plugin.ai.core.repository.JpaAiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.rest.controller.AiModelDefinitionController;
import org.simplepoint.plugin.ai.core.rest.controller.AiProviderDefinitionController;
import org.simplepoint.plugin.ai.core.service.impl.AiModelCatalogServiceImpl;
import org.simplepoint.plugin.ai.core.service.impl.AiModelDefinitionServiceImpl;
import org.simplepoint.plugin.ai.core.service.impl.AiProviderDefinitionServiceImpl;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiProviderAdapterRegistry;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real controllers, scopes, field-policy JSON and SQL; only the external network adapter is stubbed. */
class AiConfigurationLifecycleHttpTest {
  private static final String PROVIDERS = "/workbench/providers", MODELS = "/workbench/models";
  private final ObjectMapper mapper = new ObjectMapper();
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private AuthorizationContext context;
  private AiCredentialCipher cipher;
  private AiProviderAdapter adapter;
  private AiProviderDefinitionServiceImpl providerService;
  private String providerId, modelId, originalCiphertext;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:ai-config-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl").build();
    factory = new MetadataSources(registry).addAnnotatedClass(AiProviderDefinition.class).addAnnotatedClass(AiModelDefinition.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    var properties = new AiProperties(); properties.setCredentialEncryptionKey("isolated-ai-config-test-only-key");
    properties.setTenantProviderManagementEnabled(true); cipher = new AiCredentialCipher(properties);
    var provider = provider(); originalCiphertext = cipher.encrypt("test-only-original-api-key");
    provider.setCredentialCiphertext(originalCiphertext); session.persist(provider); providerId = provider.getId();
    var model = model(); session.persist(model); modelId = model.getId(); session.flush(); session.clear();
    context = new AuthorizationContext(); context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setAttributes(Map.of("X-Tenant-Id", "__platform__")); context.setUserId("test-editor");
    context.setIsAdministrator(false); context.setDataScopeType("NONE");
    context.setResources(List.of("ai.workbench.providers.create", "ai.workbench.providers.edit", "ai.workbench.providers.delete",
        "ai.workbench.providers.view", "ai.workbench.providers.test", "ai.workbench.providers.discover", "ai.workbench.providers.sync",
        "ai.workbench.models.create", "ai.workbench.models.edit", "ai.workbench.models.view", "ai.workbench.models.debug", "ai.workbench.models.delete"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var providers = mock(AiProviderDefinitionRepository.class,
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl<>(AiProviderDefinition.class, session))));
    doAnswer(call -> session.createQuery("from AiProviderDefinition where id = :id and deletedAt is null", AiProviderDefinition.class)
        .setParameter("id", call.getArgument(0)).uniqueResultOptional()).when(providers).findActiveById(anyString());
    doAnswer(call -> session.createQuery("from AiProviderDefinition where code = :code and scopeType = :scope and ((tenantId is null and :tenant is null) or tenantId = :tenant) and deletedAt is null", AiProviderDefinition.class)
        .setParameter("code", call.getArgument(0)).setParameter("scope", call.getArgument(1))
        .setParameter("tenant", call.getArgument(2)).uniqueResultOptional()).when(providers).findActiveByCodeAndScope(anyString(), any(), nullable(String.class));
    doAnswer(call -> {
      String jpql = JpaAiProviderDefinitionRepository.class.getMethod("findSelectedOptions", Set.class, AiResourceScope.class, String.class)
          .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
      return session.createQuery(jpql, AiProviderOption.class).setParameter("ids", call.getArgument(0))
          .setParameter("scopeType", call.getArgument(1)).setParameter("tenantId", call.getArgument(2)).getResultList();
    }).when(providers).findSelectedOptions(anySet(), any(), nullable(String.class));
    var models = mock(AiModelDefinitionRepository.class,
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl<>(AiModelDefinition.class, session))));
    doAnswer(call -> session.createQuery("from AiModelDefinition where id = :id and deletedAt is null", AiModelDefinition.class)
        .setParameter("id", call.getArgument(0)).uniqueResultOptional()).when(models).findActiveById(anyString());
    doAnswer(call -> session.createQuery("from AiModelDefinition where providerId = :provider and modelId = :model and deletedAt is null", AiModelDefinition.class)
        .setParameter("provider", call.getArgument(0)).setParameter("model", call.getArgument(1)).uniqueResultOptional())
        .when(models).findActiveByProviderAndModelId(anyString(), anyString());
    doAnswer(call -> session.createQuery("from AiModelDefinition where providerId = :provider and deletedAt is null", AiModelDefinition.class)
        .setParameter("provider", call.getArgument(0)).getResultList()).when(models).findAllActiveByProviderId(anyString());
    var details = mock(DetailsProviderService.class); var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    var policy = new AiScopeAccessPolicy(properties);
    providerService = new AiProviderDefinitionServiceImpl(providers, details, cipher, models, policy);
    var modelService = new AiModelDefinitionServiceImpl(models, details, providers, policy);
    adapter = mock(AiProviderAdapter.class); when(adapter.supports(any())).thenReturn(true);
    when(adapter.discoverModels(any())).thenReturn(List.of(
        new DiscoveredModel("original-model", "Original", AiModelType.LLM, "test-only-owner", null, "{}"),
        new DiscoveredModel("new-model", "New", AiModelType.LLM, "test-only-owner", null, "{}")));
    var catalog = new AiModelCatalogServiceImpl(providers, models, new AiProviderAdapterRegistry(List.of(adapter)), cipher, properties, policy);
    mvc = MockMvcBuilders.standaloneSetup(new AiProviderDefinitionController(providerService, catalog), new AiModelDefinitionController(modelService))
        .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder()
            .addModule(new FieldScopeJackson3Module()).build())).build();
    factory.getStatistics().clear();
  }

  private AiProviderDefinition provider() {
    var provider = new AiProviderDefinition(); provider.setName("Actual provider"); provider.setCode("actual-provider");
    provider.setVendor(AiProviderVendor.OPENAI); provider.setProviderType(AiProviderType.OPENAI);
    provider.setBaseUrl("https://provider.example.test/v1"); provider.setModelDiscoveryUrl("https://provider.example.test/v1/models");
    provider.setScopeType(AiResourceScope.SYSTEM); provider.setEnabled(true); provider.setAutoSyncEnabled(false); provider.setAllowPrivateNetwork(false);
    provider.setDescription("Original metadata"); provider.setLastStatus("SYNCED"); provider.setLastMessage("MODEL_SYNC_SUCCEEDED");
    return provider;
  }
  private AiModelDefinition model() {
    var model = new AiModelDefinition(); model.setProviderId(providerId); model.setModelId("original-model"); model.setDisplayName("Original model");
    model.setModelType(AiModelType.LLM); model.setScopeType(AiResourceScope.SYSTEM); model.setEnabled(false); model.setAvailable(true);
    model.setDiscovered(true); model.setTypeAutoDetected(true); model.setPricingAutoDetected(true); model.setBillingEnabled(true);
    model.setBillingCurrency("USD"); model.setInputTokenPrice(new BigDecimal("0.12345678")); model.setDescription("Original metadata"); return model;
  }
  private String body(Map<String, ?> values) throws Exception { return mapper.writeValueAsString(values); }
  private <T> T stored(Class<T> type, String id) {session.flush(); session.clear(); return session.find(type, id);}
  private void protectExcept(Class<?> type, String writable) {
    var permissions = new java.util.LinkedHashMap<String, String>();
    for (var field : type.getDeclaredFields()) permissions.put(type.getName() + "#" + field.getName(), field.getName().equals(writable) ? "EDITABLE" : "VISIBLE");
    context.setFieldPermissions(permissions);
  }
  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void providerMetadataEditRestoresProtectedRequiredConfigBeforeValidation() throws Exception {
    protectExcept(AiProviderDefinition.class, "description");
    mvc.perform(put(PROVIDERS).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("id", providerId, "description", "Edited provider")))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    var provider = stored(AiProviderDefinition.class, providerId);
    assertEquals("Edited provider", provider.getDescription()); assertEquals("Actual provider", provider.getName());
    assertEquals(originalCiphertext, provider.getCredentialCiphertext()); assertFalse(provider.getAutoSyncEnabled());
    assertEquals("SYNCED", provider.getLastStatus()); assertEquals(AiProviderType.OPENAI, provider.getProviderType());
  }
  @Test
  void modelMetadataEditPreservesProtectedProviderTypePricingAndDetectionFlags() throws Exception {
    protectExcept(AiModelDefinition.class, "description");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("id", modelId, "description", "Edited model")))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    var model = stored(AiModelDefinition.class, modelId);
    assertEquals(providerId, model.getProviderId()); assertEquals("original-model", model.getModelId());
    assertEquals(new BigDecimal("0.12345678"), model.getInputTokenPrice()); assertTrue(model.getBillingEnabled());
    assertTrue(model.getTypeAutoDetected()); assertTrue(model.getPricingAutoDetected()); assertFalse(model.getEnabled());
    assertEquals("Edited model", model.getDescription());
  }
  @Test
  void readonlyPricingCannotTriggerDerivedManualPricingFlag() throws Exception {
    context.setFieldPermissions(Map.of(AiModelDefinition.class.getName() + "#inputTokenPrice", "VISIBLE"));
    var payload = mapper.convertValue(model(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    payload.put("id", modelId); payload.put("inputTokenPrice", "9.25");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(payload))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    var model = stored(AiModelDefinition.class, modelId);
    assertEquals(new BigDecimal("0.12345678"), model.getInputTokenPrice()); assertTrue(model.getPricingAutoDetected());
  }
  @Test
  void genuinePricingEditStillDisablesAutomaticPricing() throws Exception {
    var payload = mapper.convertValue(model(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    payload.put("id", modelId); payload.put("inputTokenPrice", "0.25");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(payload))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    assertFalse(stored(AiModelDefinition.class, modelId).getPricingAutoDetected());
  }
  @Test
  void numericallyUnchangedPriceDoesNotBecomeManualBecauseOfJsonDecimalScale() throws Exception {
    session.find(AiModelDefinition.class, modelId).setInputTokenPrice(new BigDecimal("0.10000000")); session.flush();
    var payload = mapper.convertValue(model(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    payload.put("id", modelId); payload.put("inputTokenPrice", "0.1");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(payload))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    assertTrue(stored(AiModelDefinition.class, modelId).getPricingAutoDetected());
  }
  @Test
  void httpPriceRoundTripAndMetadataEditRetainMaximumExactPriceAndAutomaticPricing() throws Exception {
    var exact = new BigDecimal("99999999999.99999999");
    session.find(AiModelDefinition.class, modelId).setInputTokenPrice(exact); session.flush(); session.clear();
    var response = mvc.perform(get(MODELS).param("page", "0").param("size", "20")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].inputTokenPrice").isString())
        .andExpect(jsonPath("$.content[0].inputTokenPrice").value("99999999999.99999999")).andReturn().getResponse().getContentAsString();
    var row = mapper.readTree(response).path("content").get(0);
    var payload = new java.util.LinkedHashMap<String, Object>();
    for (String field : List.of("id", "providerId", "modelId", "displayName", "modelType", "enabled", "billingEnabled", "billingCurrency", "inputTokenPrice"))
      payload.put(field, mapper.convertValue(row.get(field), Object.class));
    payload.put("description", "Metadata without repricing");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(payload))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    var saved = stored(AiModelDefinition.class, modelId);
    assertEquals(exact, saved.getInputTokenPrice()); assertTrue(saved.getPricingAutoDetected());
    assertEquals("Metadata without repricing", saved.getDescription());
  }
  @Test
  void modelProviderNameProjectionCannotBypassHiddenProviderName() throws Exception {
    protectExcept(AiModelDefinition.class, "description");
    context.setFieldPermissions(new java.util.HashMap<>(context.getFieldPermissions()));
    context.getFieldPermissions().put(AiProviderDefinition.class.getName() + "#name", "HIDDEN");
    mvc.perform(put(MODELS).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("id", modelId, "description", "Metadata only")))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.providerName").doesNotExist());
  }
  @Test
  void discoveryIsReadOnlyAndDoesNotCreateLocalModels() throws Exception {
    mvc.perform(get(PROVIDERS + "/" + providerId + "/models/discover").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    session.flush(); assertEquals(0, factory.getStatistics().getEntityUpdateCount()); assertEquals(0, factory.getStatistics().getEntityInsertCount());
  }
  @Test
  void successfulConnectionTestPersistsStatusButNoLocalModels() throws Exception {
    mvc.perform(post(PROVIDERS + "/" + providerId + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.discoveredModelCount").value(2));
    var provider = stored(AiProviderDefinition.class, providerId); assertEquals("SUCCESS", provider.getLastStatus());
    assertNotNull(provider.getLastTestedAt()); assertEquals(originalCiphertext, provider.getCredentialCiphertext());
    assertEquals(1, factory.getStatistics().getEntityUpdateCount()); assertEquals(0, factory.getStatistics().getEntityInsertCount());
  }
  @Test
  void failedConnectionTestAlsoPersistsSafeFailureStatus() throws Exception {
    when(adapter.discoverModels(any())).thenThrow(new IllegalStateException("test-only-private-adapter-detail"));
    var response = mvc.perform(post(PROVIDERS + "/" + providerId + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("AI_PROVIDER_CONNECTION_TEST_FAILED")).andReturn();
    assertFalse(response.getResponse().getContentAsString().contains("private-adapter-detail"));
    assertEquals("FAILED", stored(AiProviderDefinition.class, providerId).getLastStatus());
    assertEquals(1, factory.getStatistics().getEntityUpdateCount());
  }
  @Test
  void synchronizationUpdatesModelsAndProviderStatus() throws Exception {
    mvc.perform(post(PROVIDERS + "/" + providerId + "/models/sync").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.updated").value(1));
    assertEquals("SYNCED", stored(AiProviderDefinition.class, providerId).getLastStatus());
    assertEquals(1, factory.getStatistics().getEntityInsertCount());
  }
  @Test
  void disabledProviderRejectsAllExternalOperationsBeforeAdapterCall() throws Exception {
    session.find(AiProviderDefinition.class, providerId).setEnabled(false); session.flush(); factory.getStatistics().clear();
    mvc.perform(post(PROVIDERS + "/" + providerId + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("AI_PROVIDER_DISABLED"));
    verify(adapter, never()).discoverModels(any()); session.flush(); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }
  @Test
  void actualSchemaAndBoundedProviderLookupAreExportableForBrowserContract() throws Exception {
    for (String path : List.of(PROVIDERS, MODELS)) {
      var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      var output = Path.of("build/generated-test-resources/ai-configuration/" + (path.equals(PROVIDERS) ? "providers" : "models") + ".json");
      Files.createDirectories(output.getParent()); Files.writeString(output, response);
      var fixture = Path.of("../../simplepoint-react/scripts/ai-configuration-schemas/" + output.getFileName());
      assertEquals(mapper.readTree(Files.readString(fixture)), mapper.readTree(response), "Browser contract must match the actual authorized HTTP schema");
    }
    mvc.perform(get(PROVIDERS + "/selected").param("ids", providerId)
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(providerId));
  }
  @Test
  void selectedProjectionLoadsNoCredentialEntityAndHonorsSourceFieldPolicy() throws Exception {
    context.setFieldPermissions(Map.of(AiProviderDefinition.class.getName() + "#name", "HIDDEN", AiProviderDefinition.class.getName() + "#enabled", "MASKED"));
    session.clear(); factory.getStatistics().clear();
    mvc.perform(get(PROVIDERS + "/selected").param("ids", providerId).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").doesNotExist()).andExpect(jsonPath("$[0].enabled").value("***"))
        .andExpect(jsonPath("$[0].code").value("actual-provider")).andExpect(jsonPath("$[0].credentialCiphertext").doesNotExist());
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
  }
  @Test
  void selectedProjectionExcludesForeignAndDeletedProvidersAndRetainsDisabledSelection() throws Exception {
    session.find(AiProviderDefinition.class, providerId).setEnabled(false);
    var foreign = provider(); foreign.setCode("foreign"); foreign.setScopeType(AiResourceScope.TENANT); foreign.setTenantId("foreign-tenant"); session.persist(foreign);
    var deleted = provider(); deleted.setCode("deleted"); deleted.setDeletedAt(java.time.Instant.now()); session.persist(deleted); session.flush();
    mvc.perform(get(PROVIDERS + "/selected").param("ids", providerId + "," + foreign.getId() + "," + deleted.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].enabled").value(false));
  }
  @Test
  void selectedLookupRejectsOversizedAndMaskedIdTargetsBeforeSql() throws Exception {
    var ids = java.util.stream.IntStream.range(0, 101).mapToObj(index -> "id-" + index).toList();
    mvc.perform(get(PROVIDERS + "/selected").param("ids", String.join(",", ids)).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest());
    assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
      context.setFieldPermissions(Map.of(AiProviderDefinition.class.getName() + "#id", "MASKED")); providerService.selectedOptions(List.of(providerId));
    });
    assertEquals(0, factory.getStatistics().getQueryExecutionCount());
  }
}
