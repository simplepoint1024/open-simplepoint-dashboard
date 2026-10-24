package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.beans.factory.ObjectProvider;

/** Navigation with real tenant eligibility and no platform resource or legacy package tables. */
class ManagedTenantResourceRoutesTest {
  private SessionFactory factory;
  private Session session;
  private ResourceServiceImpl service;
  private Tenant tenant;

  @BeforeEach
  void setUp() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:tenant-routes-" + UUID.randomUUID())
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantResource.class)
            .addAnnotatedClass(TenantResourceSetup.class)
            .addAnnotatedClass(TenantResourceEntitlement.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    tenant = new Tenant();
    tenant.setId("tenant-a");
    tenant.setName("Workspace");
    tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L);
    tenant.setResourceMode(TenantResourceMode.PLATFORM);
    session.persist(tenant);
    var setup = new TenantResourceSetup();
    setup.setTenantId(tenant.getId());
    setup.setState(TenantResourceSetup.State.READY);
    setup.setInitializedAt(Instant.now());
    session.persist(setup);
    service =
        new ResourceServiceImpl(
            mock(ResourceRepository.class),
            mock(DetailsProviderService.class),
            mock(ResourceAncestorRepository.class),
            mock(MicroAppService.class),
            emptyProvider(),
            emptyProvider(),
            session,
            mock(ResourceCatalogWriteBoundary.class),
            mock(ResourceRegistrationDeployments.class),
            org.mockito.Mockito.mock(
                org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                    .class));
    service.setTenantResourceCatalog(
        new TenantResourceCatalogImpl(
            session, mock(TenantRepository.class), mock(TenantResourceProvisioningService.class)));
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session.getTransaction().isActive()) {
      session.getTransaction().rollback();
    }
    session.close();
    factory.close();
  }

  private TenantResource resource(String id, String parent, ResourceType type) {
    var row = new TenantResource();
    row.setTenantId("tenant-a");
    row.setId(id);
    row.setCode(id);
    row.setPlatformResourceId(id);
    row.setScopeTypes(Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    row.setBaselineEntitled(true);
    row.setGrantable(type == ResourceType.PAGE);
    row.setType(type);
    row.setName(id);
    row.setParentId(parent);
    row.setPath("/" + id);
    row.setComponent(type == ResourceType.PAGE ? "app/FormRuntime" : null);
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    session.persist(row);
    return row;
  }

  private AuthorizationContext caller(AuthorizationScopeType scope, Set<String> grants) {
    var context = new AuthorizationContext();
    context.setUserId("user");
    context.setScopeType(scope);
    context.setResources(grants);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    return context;
  }

  @ParameterizedTest
  @CsvSource({"app,TENANT", "report,TENANT", "app,PERSONAL", "report,PERSONAL"})
  void managedPagesUseTheirTenantParentInBothWorkspaceTypes(
      String owner, AuthorizationScopeType scope) {
    if (scope == AuthorizationScopeType.PERSONAL) {
      tenant.setTenantType(TenantType.PERSONAL);
    }
    resource("office", null, ResourceType.MODULE);
    var page = resource("business", "office", ResourceType.PAGE);
    page.setPlatformResourceId(null);
    page.setManagedOwner(owner);
    page.setManagedKey("page:one");
    resource("unheld", "office", ResourceType.PAGE);
    session.flush();
    session.clear();
    caller(scope, Set.of("business"));
    var result = service.routes();
    assertThat(result.routes()).hasSize(1);
    var root = result.routes().iterator().next();
    assertThat(root.getCode()).isEqualTo("office");
    assertThat(root.getChildren())
        .singleElement()
        .satisfies(
            child -> {
              assertThat(child.getCode()).isEqualTo("business");
              assertThat(child.getComponent()).isEqualTo("app/FormRuntime");
              assertThat(child.getScopeTypes())
                  .containsExactly(ResourceScopeType.valueOf(scope.name()));
              assertThat(child.getRequireOrgTenant())
                  .isEqualTo(scope == AuthorizationScopeType.TENANT);
            });
  }

  @Test
  void routeNamesCarryTenantOverridesWithoutChangingPlatformIdentity() throws Exception {
    var page = resource("business", null, ResourceType.PAGE);
    page.setTitle("menu.business");
    page.setNameLocalization(new org.simplepoint.api.locale.LocalizedText("客户", "zh-CN", Map.of("en-US", "Customers")));
    session.flush();
    session.clear();
    caller(AuthorizationScopeType.TENANT, Set.of("business"));
    var node = (org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceNode) service.routes().routes().iterator().next();
    assertThat(node.getTitle()).isEqualTo("menu.business");
    assertThat(node.getCode()).isEqualTo("business");
    assertThat(node.getPath()).isEqualTo("/business");
    assertThat(node.getNameLocalization().translations()).containsEntry("en-US", "Customers");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new org.simplepoint.core.jackson.FieldScopeJacksonModule());
    for (String access : new String[]{"HIDDEN", "MASKED"}) {
      caller(AuthorizationScopeType.TENANT, Set.of("business"))
          .setFieldPermissions(Map.of(TenantResource.class.getName()+"#name", access));
      var json = mapper.readTree(mapper.writeValueAsString(node));
      if (access.equals("HIDDEN")) assertThat(json.has("nameLocalization")).isFalse();
      else assertThat(json.path("nameLocalization").asText()).isEqualTo("***");
      assertThat(json.toString()).doesNotContain("Customers");
    }
  }

  @Test
  void sameCallerLosesExpiredPageAndItsAliasWithoutPlatformFallback() {
    resource("office", null, ResourceType.MODULE);
    var page = resource("paid", "office", ResourceType.PAGE);
    page.setBaselineEntitled(false);
    var alias = resource("alias", "office", ResourceType.PAGE);
    alias.setPlatformResourceId(null);
    alias.setTargetResourceId("paid");
    var balance = new TenantResourceEntitlement();
    balance.setTenantId("tenant-a");
    balance.setId("paid");
    balance.setStartsAt(Instant.now().minusSeconds(60));
    balance.setExpiresAt(Instant.now().plusSeconds(60));
    balance.setUpdatedAt(Instant.now());
    session.persist(balance);
    caller(AuthorizationScopeType.TENANT, Set.of("alias", "paid"));
    assertThat(service.routes().routes()).hasSize(1);
    assertThat(service.routes().routes().iterator().next().getChildren())
        .singleElement()
        .satisfies(child -> assertThat(child.getCode()).isEqualTo("alias"));
    balance.setExpiresAt(Instant.now().minusSeconds(1));
    session.flush();
    session.clear();
    assertThat(service.routes().routes()).isEmpty();
  }

  @Test
  void missingCatalogNeverFallsBackEvenForAdministratorTenantNavigation() {
    caller(AuthorizationScopeType.TENANT, Set.of("page")).setIsAdministrator(true);
    service.setTenantResourceCatalog(null);
    assertThatThrownBy(service::routes)
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
