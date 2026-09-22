package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.*;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.storage.api.entity.*;
import org.simplepoint.plugin.storage.entity.StorageQuotaReadModel;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.*;
import org.simplepoint.plugin.storage.rest.controller.ObjectStorageAdminController;
import org.simplepoint.plugin.storage.service.impl.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual storage controllers, schemas, SQL aggregates, field policy and tenant isolation. */
class StorageTableQueryHttpTest {
  private SessionFactory factory;
  private Session em;
  private MockMvc mvc;
  private AuthorizationContext context;
  private String tenant;
  private String otherTenant;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", Optional.ofNullable(System.getenv("STORAGE_QUERY_DB_URL"))
            .orElseGet(() -> "jdbc:h2:mem:storage-query-" + UUID.randomUUID()))
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(ObjectStorageObject.class).addAnnotatedClass(ObjectStorageProviderConfig.class)
        .addAnnotatedClass(ObjectStorageTenantQuota.class).addAnnotatedClass(StorageQuotaReadModel.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    for (int i = 0; i < 240; i++) {
      String id = tenant(i);
      if (i == 0) tenant = id;
      if (i == 1) otherTenant = id;
      var quota = new ObjectStorageTenantQuota(); quota.setTenantId(id);
      quota.setQuotaBytes(i == 0 ? null : 100L); quota.setEnabled(i % 2 == 0);
      quota.setDescription("Quota" + String.format("%03d", i)); em.persist(quota);
      object(id, i, false); object(id, 10000, true);
      var provider = new ObjectStorageProviderConfig(); provider.setName("Provider" + String.format("%03d", i));
      provider.setCode("provider-" + i); provider.setType(ObjectStoragePlatformType.MINIO);
      provider.setBucket("bucket"); provider.setAccessKey("access-" + i); provider.setEnabled(i % 2 == 0);
      provider.setDefaultProvider(i == 0); provider.setSecretKeyCiphertext(i % 2 == 0 ? "encrypted-test" : " ");
      em.persist(provider);
    }
    for (int i = 1; i < 240; i++) object(tenant, i, false);
    em.flush(); em.clear();
    var details = details();
    var objectRepository = repository(ObjectStorageObjectRepository.class, ObjectStorageObject.class);
    var quotaRepository = repository(ObjectStorageTenantQuotaRepository.class, ObjectStorageTenantQuota.class);
    var providerRepository = repository(ObjectStorageProviderConfigRepository.class, ObjectStorageProviderConfig.class);
    var objects = new ObjectStorageObjectServiceImpl(objectRepository, details, quotaRepository, List.of(), new ObjectStorageProperties(), "test");
    var quotas = new ObjectStorageTenantQuotaServiceImpl(quotaRepository, details, objectRepository, mock(TenantRepository.class));
    var providers = new ObjectStorageProviderConfigServiceImpl(providerRepository, details, objectRepository, null, new ObjectStorageProperties(), List.of());
    ReflectionTestUtils.setField(quotas, "entityManager", em);
    ReflectionTestUtils.setField(providers, "entityManager", em);
    mvc = MockMvcBuilders.standaloneSetup(new ObjectStorageAdminController(objects, quotas, providers))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder().addModule(new FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    context = new AuthorizationContext(); context.setUserId("reader"); context.setDataScopeType("NONE");
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
  }

  private <R, E> R repository(Class<R> contract, Class<E> entity) {
    return mock(contract, withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(entity, em))));
  }

  private DetailsProviderService details() {
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    return details;
  }

  private String tenant(int index) {
    var value = new Tenant(); value.setName("Tenant" + String.format("%03d", index));
    value.setOwnerId("owner"); value.setAuthorizationVersion(0L);
    value.setTenantType(org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.ORGANIZATION);
    em.persist(value); return value.getId();
  }

  private void object(String owner, int index, boolean deleted) {
    var object = new ObjectStorageObject(); object.setTenantId(owner); object.setProviderCode("provider-0");
    object.setProviderType(ObjectStoragePlatformType.MINIO); object.setBucket("bucket"); object.setObjectKey("key-" + index);
    object.setOriginalFileName("File" + String.format("%03d", index)); object.setContentLength((long) index);
    object.setCreatedBy("another-user"); em.persist(object);
    if (deleted) object.setDeletedAt(Instant.now());
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
    if (factory != null) factory.close();
  }

  private MockHttpServletRequestBuilder query(String section) {
    return get("/platform/object-storage/" + section)
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @ParameterizedTest
  @ValueSource(strings = {"provider-configs", "objects", "quotas"})
  void actualSchemaAndOffPageQueryAreShared(String section) throws Exception {
    String field = section.equals("quotas") ? "tenantName" : section.equals("objects") ? "originalFileName" : "name";
    String value = section.equals("quotas") ? "Tenant239" : section.equals("objects") ? "File239" : "Provider239";
    mvc.perform(query(section).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"equals\",\"value\":\"" + value + "\"}}")
            .param("sort", field + ",desc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0]." + field).value(value));
    var response = mvc.perform(query(section + "/schema")).andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + field + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.deletedAt").doesNotExist()).andReturn();
    var directory = Path.of("build/generated-test-resources/storage"); Files.createDirectories(directory);
    Files.writeString(directory.resolve(section + ".json"), response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    var fixture = Path.of("../../simplepoint-react/scripts/storage-schemas/" + section + ".json");
    if (!Boolean.getBoolean("simplepoint.query.exportSchemas")) assertEquals(mapper.readTree(fixture.toFile()), mapper.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"provider-configs", "objects", "quotas"})
  void unsafeQueriesAndPageBudgetsFailClosed(String section) throws Exception {
    for (String field : List.of("deletedAt", "createdBy", "secretKeyCiphertext", "unknown")) {
      mvc.perform(query(section).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"is:null\"}}"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(query(section).param("size", "201")).andExpect(status().isBadRequest());
  }

  @Test
  void quotaAggregateFiltersCountBeforePaginationAndHandleUnlimitedAndOverused() throws Exception {
    mvc.perform(query("quotas").param("usedBytes", "than:equal:greater:230").param("sort", "usedBytes,desc").param("size", "3"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(11))
        .andExpect(jsonPath("$.content[0].usedBytes").value(28680))
        .andExpect(jsonPath("$.content[1].usedBytes").value(239));
    mvc.perform(query("quotas").param("remainingBytes", "equals:0"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(140));
    mvc.perform(query("quotas").param("remainingBytes", "is:null"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].quotaBytes").isEmpty());
    factory.getStatistics().clear();
    mvc.perform(query("quotas").param("size", "100")).andExpect(status().isOk());
    assertEquals(2, factory.getStatistics().getPrepareStatementCount(), "page plus count, no per-tenant usage/name queries");
  }

  @Test
  void objectsRemainScopedAndGlobalQuotaUsageIsNotDistortedByAmbientTenantFilter() throws Exception {
    mvc.perform(query("objects")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(240));
    mvc.perform(query("objects").param("tenantId", otherTenant)).andExpect(status().isBadRequest());
    mvc.perform(query("quotas").param("tenantName", "equals:Tenant239"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].usedBytes").value(239));
    assertNotNull(em.getEnabledFilter("tenantFilter"), "global read must not disable caller's tenant filter");
  }

  @Test
  void credentialStateIsQueryableButCredentialContentsNeverAre() throws Exception {
    mvc.perform(query("provider-configs").param("secretConfigured", "equals:true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(120))
        .andExpect(jsonPath("$.content[0].secretConfigured").value(true))
        .andExpect(jsonPath("$.content[0].secretKeyCiphertext").doesNotExist())
        .andExpect(jsonPath("$.content[0].secretKey").doesNotExist());
    mvc.perform(query("provider-configs").param("accessKey", "like:access"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }

  @Test
  void derivedValuesInheritFieldRestrictionsInQueriesSchemasAndHttp() throws Exception {
    context.setFieldPermissions(Map.of(ObjectStorageObject.class.getName() + "#contentLength", "HIDDEN"));
    mvc.perform(query("quotas")).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].usedBytes").doesNotExist())
        .andExpect(jsonPath("$.content[0].remainingBytes").doesNotExist());
    mvc.perform(query("quotas/schema")).andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields.usedBytes").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.remainingBytes").doesNotExist());
    mvc.perform(query("quotas").param("sort", "remainingBytes,desc")).andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"provider-configs", "objects", "quotas"})
  void everyDeclaredScalarUsesActualSqlTypesAndSortExpressions(String section) throws Exception {
    var schema = mapper.readTree(mvc.perform(query(section + "/schema")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    var first = mapper.readTree(mvc.perform(query(section)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("content").get(0);
    var fields = schema.path("schema").path("x-query").path("fields").fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      var value = first.get(field);
      assertNotNull(value, "Declared field missing from actual HTTP projection: " + section + "/" + field);
      var leaf = mapper.createObjectNode().put("field", field).put("op", value.isNull() ? "is:null" : "equals");
      if (!value.isNull()) leaf.set("value", value);
      var document = mapper.createObjectNode().put("version", 1); document.set("where", leaf);
      mvc.perform(query(section).param("$query", document.toString()).param("sort", field + ",desc"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)));
    }
  }

  @Test
  void aggregateBooleanTreeIntersectsHeadersAndUsesTheSameCount() throws Exception {
    mvc.perform(query("quotas").param("remainingBytes", "is:not:null").param("page", "1").param("size", "2")
        .param("sort", "usedBytes,desc", "tenantName,asc").param("$query", """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"usedBytes","op":"than:equal:greater","value":230},
            {"field":"tenantName","op":"equals","value":"Tenant001"}]},
          {"op":"not","children":[{"field":"enabled","op":"equals","value":false}]}]}}
        """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.content[0].usedBytes").value(234));
  }

  @Test
  void usageProjectionFlushesPendingChangesAndDoesNotRetainStaleEntityValues() throws Exception {
    mvc.perform(query("quotas").param("tenantName", "equals:Tenant001"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].usedBytes").value(1));
    object(otherTenant, 60, false);
    mvc.perform(query("quotas").param("usedBytes", "equals:61").param("tenantName", "equals:Tenant001"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].remainingBytes").value(39));
  }

  @Test
  void inlineReadModelIsDiscoveredByTheProductionEntityScanPattern() throws Exception {
    var scan = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
    scan.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(jakarta.persistence.Entity.class));
    String[] packages = (String[]) org.simplepoint.data.jpa.base.repository.EnableRepository.class
        .getMethod("entityBasePackages").getDefaultValue();
    assertTrue(Arrays.stream(packages).flatMap(value -> scan.findCandidateComponents(value).stream())
        .anyMatch(bean -> StorageQuotaReadModel.class.getName().equals(bean.getBeanClassName())));
  }
}
