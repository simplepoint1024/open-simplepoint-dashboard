package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class AuditIdentityDirectoryTest {
  private SessionFactory factory;
  private EntityManager em;
  private AuditIdentityDirectoryImpl directory;

  @BeforeEach
  void setup() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url", "jdbc:h2:mem:audit-labels-" + UUID.randomUUID())
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                    .applySetting("hibernate.generate_statistics", true)
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(User.class)
            .addAnnotatedClass(Role.class)
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantUserRelevance.class)
            .addAnnotatedClass(Organization.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.createEntityManager();
    directory = new AuditIdentityDirectoryImpl(em);
    em.getTransaction().begin();
    tenant("a", "研发工作空间", "owner");
    tenant("b", "Other workspace", "outsider");
    user("owner", "空间负责人", true);
    user("member", "员工・山田😀", true);
    user("removed", "Former member", true);
    user("disabled", "Disabled member", false);
    user("deleted", "Deleted user", true).setDeletedAt(Instant.EPOCH);
    user("outsider", "Other tenant", true);
    member("member", true);
    member("disabled", true);
    member("deleted", true);
    member("removed", true).setDeletedAt(Instant.EPOCH);
    organization("dept-a", "a", "研发部", true);
    organization("dept-b", "b", "Other department", true);
    organization("dept-disabled", "a", "Disabled department", false);
    organization("dept-deleted", "a", "Deleted department", true).setDeletedAt(Instant.EPOCH);
    em.getTransaction().commit();
    em.clear();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                new DefaultOAuth2AuthenticatedPrincipal(
                    "app",
                    Map.of("client_id", "app", "grant_type", "client_credentials"),
                    List.of()),
                null,
                "SCOPE_service-router.invoke"));
  }

  @AfterEach
  void close() {
    SecurityContextHolder.clearContext();
    if (em != null) {
      em.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void returnsOnlyRequestedActiveTenantIdentitiesWithExplicitDeletionChecks() throws Exception {
    var result =
        directory.resolve(
            new AuditIdentityRequest(
                "a",
                true,
                Set.of("member", "owner", "outsider", "removed", "disabled", "deleted", "unknown"),
                Set.of("dept-a", "dept-b", "dept-disabled", "dept-deleted", "unknown")));
    assertThat(result.tenantName()).isEqualTo("研发工作空间");
    assertThat(result.users())
        .containsOnlyKeys("member", "owner")
        .containsEntry("member", "员工・山田😀");
    assertThat(result.departments()).containsOnlyKeys("dept-a");
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertThat(json).doesNotContain("password", "email", "phone", "authorization", "secret");
    assertThat(
            directory
                .resolve(new AuditIdentityRequest("a", false, Set.of(), Set.of()))
                .tenantName())
        .isNull();
  }

  @Test
  void refusesOrdinaryAndAdministratorTokensBeforeReadingTheDirectory() {
    var entities = mock(EntityManager.class);
    var guarded = new AuditIdentityDirectoryImpl(entities);
    for (String authority :
        Set.of(
            "users.view",
            "organizations.view",
            "ROLE_Administrator",
            "SCOPE_service-router.invoke")) {
      SecurityContextHolder.getContext()
          .setAuthentication(new TestingAuthenticationToken("browser", null, authority));
      assertThatThrownBy(
              () ->
                  guarded.resolve(new AuditIdentityRequest("a", true, Set.of("member"), Set.of())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () -> guarded.resolve(new AuditIdentityRequest("a", true, Set.of(), Set.of())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(entities);
  }

  @Test
  void missingDeletedAndOtherTenantsDoNotResolveForeignReferences() {
    var other =
        directory.resolve(
            new AuditIdentityRequest("b", false, Set.of("member", "owner"), Set.of("dept-a")));
    assertThat(other.users()).isEmpty();
    assertThat(other.departments()).isEmpty();
    em.getTransaction().begin();
    em.find(Tenant.class, "a").setDeletedAt(Instant.EPOCH);
    em.getTransaction().commit();
    for (String tenant : Set.of("a", "missing")) {
      var result =
          directory.resolve(
              new AuditIdentityRequest(tenant, true, Set.of("member"), Set.of("dept-a")));
      assertThat(result.tenantName()).isNull();
      assertThat(result.users()).isEmpty();
      assertThat(result.departments()).isEmpty();
    }
  }

  @Test
  void labelsFollowSharedNamesAndRolesRemainInsideTheTenant() {
    em.getTransaction().begin();
    var member = em.find(User.class, "member");
    member.setName(" ");
    member.setNickname("Do not expose nickname");
    member.setFamilyName("山田");
    member.setGivenName("太郎");
    var owner = em.find(User.class, "owner");
    owner.setName(null);
    role("role-1", "a", "approval", "审批人员");
    role("role-2", "a", "approval", "财务人员");
    role("role-3", "a", "approval", "审批人员");
    role("role-4", "b", "approval", "Foreign role");
    role("role-5", "a", "removed", "Deleted role").setDeletedAt(Instant.EPOCH);
    em.getTransaction().commit();
    em.clear();
    var labels =
        directory.resolve(
            new AuditIdentityRequest(
                "a",
                false,
                Set.of("member", "owner"),
                Set.of(),
                Set.of("approval", "removed", "missing")));
    assertThat(labels.users())
        .containsEntry("member", "山田 太郎")
        .containsEntry("owner", "owner@example.invalid");
    assertThat(labels.roles()).containsExactlyEntriesOf(Map.of("approval", "审批人员 / 财务人员"));
  }

  @Test
  void endUserGrantWithServiceScopeStillCannotReadLabels() {
    var entities = mock(EntityManager.class);
    var guarded = new AuditIdentityDirectoryImpl(entities);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                new DefaultOAuth2AuthenticatedPrincipal(
                    "app",
                    Map.of("client_id", "app", "grant_type", "authorization_code"),
                    List.of()),
                null,
                "SCOPE_service-router.invoke"));
    assertThatThrownBy(
            () -> guarded.resolve(new AuditIdentityRequest("a", false, Set.of(), Set.of())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(entities);
  }

  @Test
  void versionTwoIsRegisteredAndReturnsTypedLabelsThroughTheWireCodec() throws Exception {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean(AuditIdentityDirectoryImpl.class, () -> directory)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry =
                  context.getBean(
                      com.simplepoint.service.router.registry.LocalServiceRegistry.class);
              assertThat(registry.find("saas.audit-identities", "1")).isEmpty();
              assertThat(registry.find("saas.audit-identities", "2")).isPresent();
              var codec =
                  new com.simplepoint.service.router.invocation.ServiceRouterJsonCodec(
                      context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class));
              var dispatcher =
                  context.getBean(
                      com.simplepoint.service.router.invocation.ServiceInvocationDispatcher.class);
              var request =
                  new AuditIdentityRequest("a", false, Set.of("member"), Set.of(), Set.of());
              var response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new com.simplepoint.service.router.invocation.RemoteRequest(
                                  "saas.audit-identities",
                                  "2",
                                  "resolve",
                                  List.of(request),
                                  "labels"))));
              assertThat(response.success()).isTrue();
              var method =
                  org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory.class
                      .getMethod("resolve", AuditIdentityRequest.class);
              assertThat(
                      codec.result(
                          org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory
                              .class,
                          method,
                          response.data()))
                  .isEqualTo(directory.resolve(request));
            });
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @com.simplepoint.service.router.annotation.EnableServiceRouter(
      basePackages = "org.simplepoint.plugin.rbac.tenant.api.service")
  static class Router {}

  @Test
  void excessiveDuplicateAuthorityRowsFailInsteadOfReturningPartialLabels() {
    em.getTransaction().begin();
    for (int index = 0; index < 501; index++) {
      role("bulk-" + index, "a", "bulk", "Role " + index);
    }
    em.getTransaction().commit();
    em.clear();
    assertThatThrownBy(
            () ->
                directory.resolve(
                    new AuditIdentityRequest("a", false, Set.of(), Set.of(), Set.of("bulk"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("result limit");
  }

  private Role role(String id, String tenant, String authority, String name) {
    var value = new Role();
    value.setId(id);
    value.setTenantId(tenant);
    value.setAuthority(authority);
    value.setRoleName(name);
    em.persist(value);
    return value;
  }

  private void tenant(String id, String name, String owner) {
    var value = new Tenant();
    value.setId(id);
    value.setName(name);
    value.setOwnerId(owner);
    value.setAuthorizationVersion(0L);
    em.persist(value);
  }

  private User user(String id, String name, boolean enabled) {
    var value = new User();
    value.setId(id);
    value.setUsername(id);
    value.setName(name);
    value.setEnabled(enabled);
    value.setPassword("secret");
    value.setEmail(id + "@example.invalid");
    em.persist(value);
    return value;
  }

  private TenantUserRelevance member(String user, boolean enabled) {
    var value = new TenantUserRelevance();
    value.setTenantId("a");
    value.setUserId(user);
    value.setEnabled(enabled);
    em.persist(value);
    return value;
  }

  private Organization organization(String id, String tenant, String name, boolean enabled) {
    var value = new Organization();
    value.setId(id);
    value.setTenantId(tenant);
    value.setCode(id);
    value.setName(name);
    value.setEnabled(enabled);
    em.persist(value);
    return value;
  }
}
