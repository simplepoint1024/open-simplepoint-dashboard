package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActivationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAdministrationQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAssignmentService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageRevocationService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantPackageAdministrationController;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantPackageController;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantPackageExceptionHandler;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantPackageActivationServiceImpl;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantPackageAssignmentServiceImpl;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantPackageQueryServiceImpl;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantPackageRevocationServiceImpl;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP adapters and transactional domain providers; transport authentication is separate. */
class TenantPackageHttpTest {
  private static final String CURRENT = "/tenants/current-packages";
  private static final String ADMIN = "/platform/tenants/tenant-a/package-instances";
  private SessionFactory factory;
  private EntityManager em;
  private JpaTransactionManager transactions;
  private MockMvc mvc;
  private AuthorizationContext actor;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url", "jdbc:h2:mem:package-http-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    var metadata = new MetadataSources(registry);
    for (var entity :
        new Class<?>[] {
          Tenant.class,
          Resource.class,
          TenantResource.class,
          TenantPackageInstance.class,
          TenantPackageResourceDefinition.class,
          TenantResourceCredit.class,
          TenantResourceEntitlement.class,
          Package.class,
          Application.class,
          ApplicationResourceRelevance.class,
          PackageApplicationRelevance.class
        }) {
      metadata.addAnnotatedClass(entity);
    }
    factory = metadata.buildMetadata().buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    transactions = new JpaTransactionManager(factory);
    write(
        () -> {
          em.createNativeQuery(
                  "create table simpoint_resource_catalog_revision"
                      + " (id varchar(32) primary key, revision bigint not null)")
              .executeUpdate();
          em.createNativeQuery(
                  "insert into simpoint_resource_catalog_revision values ('catalog',0)")
              .executeUpdate();
        });
    write(
        () -> {
          for (String id : Set.of("tenant-a", "tenant-b")) {
            var tenant = new Tenant();
            tenant.setId(id);
            tenant.setName(id);
            tenant.setOwnerId("owner");
            tenant.setTenantType(TenantType.ORGANIZATION);
            tenant.setAuthorizationVersion(0L);
            em.persist(tenant);
          }
          var product = new Package();
          product.setCode("STANDARD");
          product.setName("Standard");
          product.setEnabled(true);
          product.setDurationDays(30);
          em.persist(product);
          var application = new Application();
          application.setCode("APP");
          application.setName("Application");
          application.setEnabled(true);
          em.persist(application);
          var membership = new PackageApplicationRelevance();
          membership.setPackageCode("STANDARD");
          membership.setApplicationCode("APP");
          em.persist(membership);
          var resource = new Resource();
          resource.setId("host");
          resource.setCode("app.runtime.host");
          resource.setName("Runtime host");
          resource.setType(ResourceType.PAGE);
          resource.setComponent("app/FormRuntime");
          resource.setScopeTypes(Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
          resource.setGrantable(false);
          resource.setDisabled(false);
          resource.setPublicAccess(false);
          resource.setRequireOrgTenant(false);
          em.persist(resource);
          var relation = new ApplicationResourceRelevance();
          relation.setApplicationCode("APP");
          relation.setResourceCode(resource.getCode());
          em.persist(relation);
        });
    var versions = mock(ResourceAuthorizationVersionService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new TenantPackageController(
                    proxy(new TenantPackageQueryServiceImpl(em), TenantPackageQueryService.class),
                    proxy(
                        new TenantPackageActivationServiceImpl(em, versions),
                        TenantPackageActivationService.class)),
                new TenantPackageAdministrationController(
                    proxy(
                        new TenantPackageAssignmentServiceImpl(em),
                        TenantPackageAssignmentService.class),
                    proxy(
                        new TenantPackageRevocationServiceImpl(em, versions),
                        TenantPackageRevocationService.class),
                    proxy(
                        new TenantPackageQueryServiceImpl(em),
                        TenantPackageAdministrationQueryService.class)))
            .setControllerAdvice(new TenantPackageExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (factory != null) {
      factory.close();
    }
  }

  private <T> T proxy(T target, Class<T> contract) {
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return contract.cast(proxy.getProxy());
  }

  private void write(Runnable work) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
  }

  private void actor(
      String user, AuthorizationScopeType scope, String tenant, boolean administrator) {
    actor = new AuthorizationContext();
    actor.setUserId(user);
    actor.setScopeType(scope);
    actor.setIsAdministrator(administrator);
    actor.setAttributes(tenant == null ? Map.of() : Map.of("X-Tenant-Id", tenant));
  }

  private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, actor));
  }

  private String allocate() throws Exception {
    actor("platform-admin", AuthorizationScopeType.PLATFORM, null, true);
    String id = UUID.randomUUID().toString();
    call(post(ADMIN)
            .contentType("application/json")
            .content(
                json.writeValueAsString(Map.of("allocationId", id, "packageCode", "STANDARD"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"))
        .andExpect(jsonPath("$.activatedAt").isEmpty());
    return id;
  }

  @ParameterizedTest
  @CsvSource({"TENANT,ORGANIZATION", "PERSONAL,PERSONAL"})
  void allocationActivationRetryDetailsAndRevocationUseRealTransactions(
      AuthorizationScopeType scope, TenantType type) throws Exception {
    write(() -> em.find(Tenant.class, "tenant-a").setTenantType(type));
    final String id = allocate();
    actor("owner", scope, "tenant-a", false);
    call(get(CURRENT + "/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buttons[1].key").value("activate"));
    call(get(CURRENT).param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].allowedActions[1]").value("activate"));
    String receipt =
        call(post(CURRENT + "/" + id + "/activate"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String repeated =
        call(post(CURRENT + "/" + id + "/activate"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(repeated)).isEqualTo(json.readTree(receipt));
    call(get(CURRENT + "/" + id + "/resources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].currentState").value("ACTIVE"));
    call(get(CURRENT + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedActions.length()").value(1));
    actor("platform-admin", AuthorizationScopeType.PLATFORM, null, true);
    call(post(ADMIN + "/" + id + "/revoke")).andExpect(status().isOk());
    actor("owner", scope, "tenant-a", false);
    call(post(CURRENT + "/" + id + "/activate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TENANT_PACKAGE:INSTANCE_REVOKED"))
        .andExpect(jsonPath("$.messageKey").isString());
  }

  @Test
  void platformAdministrationReadsOnlyTheSelectedTenantAndOffersNoOwnerActivation()
      throws Exception {
    final String id = allocate();
    final String foreign = UUID.randomUUID().toString();
    call(post(ADMIN.replace("tenant-a", "tenant-b"))
            .contentType("application/json")
            .content(
                json.writeValueAsString(
                    Map.of("allocationId", foreign, "packageCode", "STANDARD"))))
        .andExpect(status().isOk());
    String schema =
        call(get(ADMIN + "/schema"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.buttons[0].key").value("allocate"))
            .andExpect(jsonPath("$.buttons[2].key").value("revoke"))
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var output =
        java.nio.file.Path.of(
            "build/generated-test-resources/tenant-package-administration-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, schema);
    call(get(ADMIN).param("page", "0").param("size", "1").param("state", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(id))
        .andExpect(jsonPath("$.content[0].allowedActions[1]").value("revoke"));
    call(get(ADMIN + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedActions[1]").value("revoke"));
    call(get(ADMIN + "/" + id + "/resources")).andExpect(status().isOk());
    call(get(ADMIN + "/" + foreign)).andExpect(status().isNotFound());
    call(get(ADMIN + "/" + foreign + "/resources")).andExpect(status().isNotFound());
    call(get(ADMIN.replace("tenant-a", "missing"))).andExpect(status().isNotFound());
    call(post(ADMIN + "/" + id + "/revoke")).andExpect(status().isOk());
    call(get(ADMIN + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedActions.length()").value(1));
    actor("owner", AuthorizationScopeType.TENANT, "tenant-a", true);
    call(get(ADMIN + "/schema")).andExpect(status().isForbidden());
    call(get(ADMIN)).andExpect(status().isForbidden());
    call(get(ADMIN + "/" + id + "/resources")).andExpect(status().isForbidden());
    actor("ordinary-platform-user", AuthorizationScopeType.PLATFORM, null, false);
    call(get(ADMIN + "/schema")).andExpect(status().isForbidden());
  }

  @Test
  void exportsActualOwnerSchemaForBrowserContract() throws Exception {
    actor("owner", AuthorizationScopeType.TENANT, "tenant-a", false);
    String schema =
        call(get(CURRENT + "/schema"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema.properties.state.oneOf").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var output = java.nio.file.Path.of("build/generated-test-resources/tenant-package-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, schema);
  }

  @Test
  void administratorsAndForeignTenantOwnersCannotActivateOrReadAnotherInstance() throws Exception {
    final String id = allocate();
    actor("admin", AuthorizationScopeType.TENANT, "tenant-a", true);
    call(get(CURRENT + "/schema")).andExpect(status().isForbidden());
    call(get(CURRENT + "/" + id)).andExpect(status().isForbidden());
    call(post(CURRENT + "/" + id + "/activate")).andExpect(status().isForbidden());
    actor("owner", AuthorizationScopeType.TENANT, "tenant-b", false);
    call(get(CURRENT + "/" + id)).andExpect(status().isNotFound());
    call(get(CURRENT + "/" + id + "/resources")).andExpect(status().isNotFound());
    call(post(CURRENT + "/" + id + "/activate")).andExpect(status().isNotFound());
    call(post(ADMIN + "/" + id + "/revoke")).andExpect(status().isForbidden());
  }

  @Test
  void unavailableDefinitionRollsBackActivationAndPreservesPendingInstance() throws Exception {
    final String id = allocate();
    write(() -> em.find(Resource.class, "host").setDisabled(true));
    actor("owner", AuthorizationScopeType.TENANT, "tenant-a", false);
    call(post(CURRENT + "/" + id + "/activate")).andExpect(status().isServiceUnavailable());
    call(get(CURRENT + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"));
    write(
        () -> {
          assertThat(
                  em.createQuery("select count(c) from TenantResourceCredit c", Long.class)
                      .getSingleResult())
              .isZero();
          assertThat(
                  em.createQuery("select count(r) from TenantResource r", Long.class)
                      .getSingleResult())
              .isZero();
        });
  }
}
