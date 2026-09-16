package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.plugin.rbac.core.api.pojo.command.*;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlatformAccountDatabaseTest {
  SessionFactory factory;
  Session em;
  PlatformAccountServiceImpl accounts;
  TenantMemberServiceImpl members;
  ResourceAuthorizationVersionService versions = mock(ResourceAuthorizationVersionService.class);
  PlatformStepUpService stepUp = mock(PlatformStepUpService.class);
  TenantRepository tenants = mock(TenantRepository.class);
  User root;
  User member;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:accounts-" + UUID.randomUUID() + ";LOCK_TIMEOUT=5000")
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(User.class)
        .addAnnotatedClass(PlatformIdentity.class).addAnnotatedClass(PlatformSecurityState.class)
        .addAnnotatedClass(PlatformSecurityAudit.class).addAnnotatedClass(PlatformStepUpAttempt.class)
        .addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantUserRelevance.class)
        .addAnnotatedClass(Organization.class).addAnnotatedClass(UserRoleRelevance.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    PlatformSecurityState state = new PlatformSecurityState();
    state.setId("platform");
    em.persist(state);
    root = user("root", true);
    member = user("member", false);
    em.flush();
    accounts = service(em);
    members = new TenantMemberServiceImpl(em, versions);
    context(root.getId(), AuthorizationScopeType.PLATFORM, null);
  }

  PlatformAccountServiceImpl service(Session session) {
    return new PlatformAccountServiceImpl(session, mock(UsersService.class), stepUp, tenants, versions,
        mock(org.simplepoint.api.security.service.DetailsProviderService.class));
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {
      if (em.getTransaction().isActive()) em.getTransaction().rollback();
      em.close();
    }
    if (factory != null) factory.close();
  }

  User user(String name, boolean superAdmin) {
    User user = new User();
    user.setName(name); user.setEmail(name + "@example.com"); user.setSuperAdmin(superAdmin);
    user.setPassword("hashed-not-exposed");
    em.persist(user);
    return user;
  }

  void context(String id, AuthorizationScopeType scope, String tenant) {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId(id); ctx.setScopeType(scope);
    ctx.setActorRole(AuthorizationActorRole.TENANT_OWNER);
    ctx.setIsAdministrator(false);
    ctx.setResources(new HashSet<>(Set.of("users.view")));
    ctx.setAttributes(tenant == null ? Map.of() : Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);
  }

  PlatformAccountCommand identity(boolean superAdmin, PlatformRole... roles) {
    PlatformAccountCommand command = new PlatformAccountCommand();
    command.setRevision(0L); command.setReason("approved change");
    command.setRoles(Set.of(roles)); command.setSuperAdmin(superAdmin);
    command.setConfirmationPassword("never-in-audit");
    return command;
  }

  @Test
  void independentPlatformRolesPersistWithoutGrantingSuperAdminAndAuditExcludesCredentials() {
    var result = accounts.assignIdentity(member.getId(), identity(false, PlatformRole.PLATFORM_ADMIN));
    em.flush(); em.clear();
    assertThat(result.superAdmin()).isFalse();
    assertThat(result.roles()).containsExactly(PlatformRole.PLATFORM_ADMIN);
    assertThat(em.find(PlatformIdentity.class, member.getId()).getRoles()).containsExactly(PlatformRole.PLATFORM_ADMIN);
    assertThat(em.find(User.class, member.getId()).getAuthorizationVersion()).isEqualTo(1);
    var audit = accounts.audit(java.util.Map.of(), PageRequest.of(0, 20)).getContent().getFirst();
    assertThat(audit.getAfterState()).contains("PLATFORM_ADMIN").doesNotContain("never-in-audit", "hashed-not-exposed");
    context(member.getId(), AuthorizationScopeType.PLATFORM, null);
    assertThat(accounts.list(java.util.Map.of("keyword", "member"), PageRequest.of(0, 100)).getContent()).hasSize(1);
    assertThat(accounts.list(java.util.Map.of("keyword", "%"), PageRequest.of(0, 20)).getContent()).isEmpty();
    assertThatThrownBy(() -> accounts.assignIdentity(root.getId(), identity(true)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void forgedTenantPrivilegesCannotCallPlatformEndpoint() {
    context(member.getId(), AuthorizationScopeType.TENANT, "tenant");
    AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(() -> accounts.assignIdentity(member.getId(), identity(true)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(stepUp);
  }

  @Test
  void lastRootCannotBeRevokedOrDisabledAndStaleRevisionsFail() {
    assertThatThrownBy(() -> accounts.assignIdentity(root.getId(), identity(false)))
        .isInstanceOf(AccessDeniedException.class).hasMessageContaining("最后一个");
    PlatformAccountCommand disable = new PlatformAccountCommand();
    disable.setRevision(0L); disable.setReason("disable"); disable.setEnabled(false);
    assertThatThrownBy(() -> accounts.update(root.getId(), disable)).isInstanceOf(AccessDeniedException.class);
    var stale = identity(false, PlatformRole.AUDITOR);
    stale.setRevision(99L);
    assertThatThrownBy(() -> accounts.assignIdentity(member.getId(), stale)).isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(accounts.audit(java.util.Map.of(), PageRequest.of(0, 20))).isEmpty();
  }

  @Test
  void ordinaryAccountAdminCannotEditPrivilegedAccountsOrMassAssignIdentity() {
    accounts.assignIdentity(member.getId(), identity(false, PlatformRole.ACCOUNT_ADMIN));
    em.flush();
    context(member.getId(), AuthorizationScopeType.PLATFORM, null);
    PlatformAccountCommand change = new PlatformAccountCommand();
    change.setReason("change"); change.setName("renamed"); change.setRevision(0L);
    assertThatThrownBy(() -> accounts.update(root.getId(), change)).isInstanceOf(AccessDeniedException.class);
    change.setSuperAdmin(true);
    assertThatThrownBy(() -> accounts.update(member.getId(), change)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoRootsCannotConcurrentlyRevokeTheLastRoot() throws Exception {
    accounts.assignIdentity(member.getId(), identity(true));
    em.getTransaction().commit();
    var start = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (String id : List.of(root.getId(), member.getId())) {
        tasks.add(executor.submit(() -> {
          try (var session = factory.openSession()) {
            session.beginTransaction();
            context(id, AuthorizationScopeType.PLATFORM, null);
            var command = identity(false);
            command.setRevision(session.find(User.class, id).getAuthorizationVersion());
            start.await();
            try {
              service(session).assignIdentity(id, command);
              session.getTransaction().commit();
              return true;
            } catch (AccessDeniedException expected) {
              session.getTransaction().rollback();
              return false;
            } finally {
              RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
            }
          }
        }));
      }
      start.countDown();
      assertThat(List.of(tasks.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
          tasks.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
      em.clear();
      assertThat(em.createQuery("select count(u) from User u where u.superAdmin=true", Long.class).getSingleResult()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  Tenant tenant(String name) {
    Tenant tenant = new Tenant();
    tenant.setName(name); tenant.setOwnerId(root.getId()); tenant.setAuthorizationVersion(0L);
    em.persist(tenant);
    return tenant;
  }

  TenantUserRelevance membership(Tenant tenant) {
    TenantUserRelevance rel = new TenantUserRelevance();
    rel.setTenantId(tenant.getId()); rel.setUserId(member.getId());
    em.persist(rel);
    return rel;
  }

  @Test
  void memberChangesAndRemovalAreTenantLocal() {
    Tenant first = tenant("first");
    Tenant second = tenant("second");
    var firstMembership = membership(first);
    Organization organization = new Organization();
    organization.setTenantId(first.getId());
    organization.setName("研发中心");
    organization.setCode("RND");
    organization.setEnabled(true);
    em.persist(organization);
    firstMembership.setOrgId(organization.getId());
    var otherMembership = membership(second);
    UserRoleRelevance role = new UserRoleRelevance();
    role.setTenantId(second.getId()); role.setUserId(member.getId()); role.setRoleId("other-role");
    em.persist(role); em.flush();
    context(root.getId(), AuthorizationScopeType.TENANT, first.getId());
    var memberPage = members.list(PageRequest.of(0, 20));
    assertThat(memberPage.getTotalElements()).isEqualTo(1);
    assertThat(memberPage.getContent()).singleElement()
        .satisfies(view -> assertThat(view.orgName()).isEqualTo("研发中心"));
    members.update(member.getId(), new TenantMemberCommand(null, null, false, firstMembership.getRevision()));
    em.flush();
    assertThat(member.getEnabled()).isTrue();
    assertThat(otherMembership.getEnabled()).isTrue();
    members.remove(member.getId());
    em.flush(); em.clear();
    assertThat(em.find(User.class, member.getId())).isNotNull();
    assertThat(em.find(TenantUserRelevance.class, firstMembership.getId())).isNull();
    assertThat(em.find(TenantUserRelevance.class, otherMembership.getId())).isNotNull();
    assertThat(em.find(UserRoleRelevance.class, role.getId())).isNotNull();
  }

  @Test
  void foreignOrganizationAndMissingMembershipAreRejected() {
    Tenant first = tenant("first");
    Tenant second = tenant("second");
    Organization org = new Organization();
    org.setTenantId(second.getId()); org.setName("foreign"); org.setCode("foreign"); org.setEnabled(true);
    em.persist(org); em.flush();
    context(root.getId(), AuthorizationScopeType.TENANT, first.getId());
    assertThatThrownBy(() -> members.add(new TenantMemberCommand(member.getEmail(), org.getId(), true, null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> members.update(member.getId(), new TenantMemberCommand(null, null, true, 0L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectedStepUpCommitsAttemptEvenWhenOuterTransactionRollsBack() {
    em.getTransaction().commit();
    var manager = new org.springframework.orm.jpa.JpaTransactionManager(factory);
    var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
    var proxy = new org.springframework.aop.framework.ProxyFactory(
        new PlatformStepUpService(shared, mock(PasswordEncoder.class)));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    var verifier = (PlatformStepUpService) proxy.getProxy();
    var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertThatThrownBy(() -> outer.execute(status -> {
      verifier.verify(root.getId(), identity(true));
      return null;
    })).isInstanceOf(AccessDeniedException.class);
    em.clear();
    assertThat(em.find(PlatformStepUpAttempt.class, root.getId()).getFailures()).isEqualTo(1);
    assertThat(em.createQuery("select count(a) from PlatformSecurityAudit a where a.action='STEP_UP_REJECTED'", Long.class)
        .getSingleResult()).isEqualTo(1);
  }

  @Test
  void totpCodeCannotBeReplayedAfterSuccessfulStepUp() {
    PasswordEncoder passwords = mock(PasswordEncoder.class);
    when(passwords.matches(anyString(), anyString())).thenReturn(true);
    root.setTwoFactorEnabled(true);
    // RFC test secret; the six-digit SHA1 code for counter 1 is 287082.
    root.setTwoFactorSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    em.flush();
    var verifier = new PlatformStepUpService(em, passwords,
        java.time.Clock.fixed(java.time.Instant.ofEpochSecond(59), java.time.ZoneOffset.UTC));
    var command = identity(true);
    command.setConfirmationCode("287082");
    verifier.verify(root.getId(), command);
    assertThat(em.find(PlatformStepUpAttempt.class, root.getId()).getLastTotpStep()).isEqualTo(1L);
    assertThatThrownBy(() -> verifier.verify(root.getId(), command)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void invalidStepUpIsRateLimitedAndDoesNotChangeIdentity() {
    PasswordEncoder passwords = mock(PasswordEncoder.class);
    PlatformStepUpService verifier = new PlatformStepUpService(em, passwords);
    var command = identity(true);
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> verifier.verify(root.getId(), command)).isInstanceOf(AccessDeniedException.class);
    }
    em.flush();
    assertThat(em.find(PlatformStepUpAttempt.class, root.getId()).getFailures()).isEqualTo(5);
    assertThatThrownBy(() -> verifier.verify(root.getId(), command)).hasMessageContaining("次数过多");
    verify(passwords, times(5)).matches("never-in-audit", "hashed-not-exposed");
    assertThat(member.getSuperAdmin()).isFalse();
  }
}
