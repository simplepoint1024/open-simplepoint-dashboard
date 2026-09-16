package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.rbac.core.base.repository.JpaFieldScopeRepository;
import org.simplepoint.plugin.rbac.core.base.repository.JpaRoleScopeBindingRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class ScopePolicyDatabaseTest {
  SessionFactory factory;
  Session session;
  JpaFieldScopeRepository policies;
  JpaRoleScopeBindingRepository bindings;
  FieldScopeServiceImpl service;
  ResourceAuthorizationVersionService versions = mock(ResourceAuthorizationVersionService.class);

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:policy-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(FieldScope.class)
        .addAnnotatedClass(FieldScopeEntry.class).addAnnotatedClass(RoleScopeBinding.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    var repositoryFactory = new JpaRepositoryFactory(session);
    repositoryFactory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    policies = repositoryFactory.getRepository(JpaFieldScopeRepository.class);
    bindings = new JpaRepositoryFactory(session).getRepository(JpaRoleScopeBindingRepository.class);
    service = new FieldScopeServiceImpl(policies, null, versions, new FieldScopeCatalog(session));
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("u1");
    context.setAttributes(Map.of("X-Tenant-Id", "t1"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) factory.close();
  }

  FieldScope policy(String tenant) {
    FieldScope policy = new FieldScope();
    policy.setTenantId(tenant);
    policy.setName("policy");
    session.persist(policy);
    session.flush();
    return policy;
  }

  FieldScopeEntry entry(String field, FieldAccessType access) {
    FieldScopeEntry entry = new FieldScopeEntry();
    entry.setResource(FieldScope.class.getName());
    entry.setField(field);
    entry.setAccess(access);
    return entry;
  }

  @Test
  void foreignPolicyCannotBeReplacedById() {
    var foreign = policy("t2");
    assertThatThrownBy(() -> service.replaceEntries(foreign.getId(), List.of(entry("name", FieldAccessType.HIDDEN))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(foreign.getEntries()).isEmpty();
    verifyNoInteractions(versions);
  }

  @Test
  void replacementReusesRowsAndRemovesOrphansWithoutUniqueCollisions() {
    var policy = policy("t1");
    service.replaceEntries(policy.getId(), List.of(entry("name", FieldAccessType.HIDDEN), entry("description", FieldAccessType.MASKED)));
    session.flush();
    String firstEntryId = policy.getEntries().getFirst().getId();
    service.replaceEntries(policy.getId(), List.of(entry("name", FieldAccessType.VISIBLE)));
    session.flush();
    session.clear();
    var updated = policies.findByIdAndTenantIdAndDeletedAtIsNull(policy.getId(), "t1").orElseThrow();
    assertThat(updated.getEntries()).hasSize(1);
    assertThat(updated.getEntries().getFirst().getId()).isEqualTo(firstEntryId);
    assertThat(updated.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.VISIBLE);
    assertThat(session.createQuery("select count(e) from FieldScopeEntry e", Long.class).getSingleResult()).isEqualTo(1);
    verify(versions, times(2)).refreshTenant("t1");
  }

  @Test
  void invalidOrDuplicateFieldRulesLeaveExistingRulesUntouched() {
    var policy = policy("t1");
    service.replaceEntries(policy.getId(), List.of(entry("name", FieldAccessType.HIDDEN)));
    assertThatThrownBy(() -> service.replaceEntries(policy.getId(), List.of(entry("missing", FieldAccessType.VISIBLE))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.replaceEntries(policy.getId(),
        List.of(entry("name", FieldAccessType.VISIBLE), entry("name", FieldAccessType.HIDDEN))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(policy.getEntries()).hasSize(1);
    assertThat(policy.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.HIDDEN);
  }

  @Test
  void independentBindingPersistsWithoutResourceRowsAndIsTenantQualified() {
    var binding = new RoleScopeBinding();
    binding.setTenantId("t1");
    binding.setRoleId("new-role");
    bindings.saveAndFlush(binding);
    session.clear();
    var stored = bindings.findByTenantIdAndRoleId("t1", "new-role").orElseThrow();
    assertThat(stored.getRevision()).isZero();
    assertThat(bindings.findByTenantIdAndRoleId("t2", "new-role")).isEmpty();
    stored.setDataScopeId("scope-id");
    bindings.saveAndFlush(stored);
    assertThat(stored.getRevision()).isEqualTo(1);
  }
}
