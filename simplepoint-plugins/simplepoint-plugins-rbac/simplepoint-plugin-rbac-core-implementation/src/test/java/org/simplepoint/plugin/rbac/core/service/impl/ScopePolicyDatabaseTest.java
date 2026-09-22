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
    factory = new MetadataSources(registry).addAnnotatedClass(FieldScope.class).addAnnotatedClass(Resource.class)
        .addAnnotatedClass(RoleResourceGrant.class).addAnnotatedClass(FieldScopeEntry.class).addAnnotatedClass(RoleScopeBinding.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    var repositoryFactory = new JpaRepositoryFactory(session);
    repositoryFactory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    policies = repositoryFactory.getRepository(JpaFieldScopeRepository.class);
    bindings = new JpaRepositoryFactory(session).getRepository(JpaRoleScopeBindingRepository.class);
    service = new FieldScopeServiceImpl(policies, null, versions, new FieldScopeCatalog(session), new PermissionPolicyGuard(session)) {
      @Override protected java.util.Set<String> getAllFieldNames(Class<FieldScope> clazz) {
        return new java.util.HashSet<>(List.of("name", "description", "entries"));
      }
    };
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("u1");
    context.setResources(java.util.Set.of("people.view"));
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
  void deletingRoleBoundPolicyIsRejectedWithoutChangingItsEntries() {
    var policy = policy("t1");
    var grant = new RoleResourceGrant();
    grant.setTenantId("t1"); grant.setRoleId("role-1"); grant.setResourceCode("orders.view");
    grant.setFieldScopeId(policy.getId()); grant.setPagePolicy(true); session.persist(grant); session.flush();
    assertThatThrownBy(() -> service.removeByIds(java.util.Set.of(policy.getId())))
        .isInstanceOf(org.simplepoint.core.permission.InvalidPermissionPolicyException.class)
        .hasMessageContaining("正在被角色使用");
    assertThat(policy.getDeletedAt()).isNull();
    verifyNoInteractions(versions);
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

  void page(String code, boolean disabled) {
    var field = new org.simplepoint.core.permission.PermissionModelDescriptor.Field("java.lang.String", "姓名", List.of("equals"), List.of());
    var model = new org.simplepoint.core.permission.PermissionModelDescriptor("org.simplepoint.remote.Person", true, true,
        "id", "orgId", Map.of("name", field, "email", field));
    var resource = new Resource(); resource.setCode(code); resource.setName("人员");
    resource.setType(ResourceType.PAGE); resource.setDisabled(disabled);
    resource.setPermissionMetadata(new org.simplepoint.core.permission.PermissionPageDescriptor(List.of(model)).toJson());
    session.persist(resource); session.flush();
  }

  FieldScopeEntry remoteEntry(String field) {
    var entry = entry(field, FieldAccessType.MASKED);
    entry.setResource("org.simplepoint.remote.Person"); return entry;
  }

  @Test void pageCatalogOnlyAdvertisesAuthorizedActivePagesWithFieldMetadata() {
    page("people.view", false); page("secret.view", false); page("disabled.view", true);
    var catalog = service.pageCatalog();
    assertThat(catalog).containsOnlyKeys("people.view");
    assertThat(catalog.get("people.view").title()).isEqualTo("人员");
    assertThat(catalog.get("people.view").models().getFirst().properties()).containsKeys("name", "email");
  }

  @Test void configuredCreateAndUpdateSaveMetadataAndCanonicalFieldRulesTogether() {
    page("people.view", false);
    var created = service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "人员权限", "说明", List.of(remoteEntry("name"), remoteEntry("email"))));
    session.flush();
    assertThat(created.getTenantId()).isEqualTo("t1");
    assertThat(created.getEntries()).hasSize(2).allMatch(e -> e.getFieldScopeId().equals(created.getId()) && e.getTenantId().equals("t1"));
    String rowId = created.getEntries().stream().filter(e -> e.getField().equals("name")).findFirst().orElseThrow().getId();
    var changed = remoteEntry("name"); changed.setAccess(FieldAccessType.HIDDEN);
    service.updateConfigured(created.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "更新名称", "新说明", List.of(changed)));
    session.flush(); session.clear();
    var saved = policies.findByIdAndTenantIdAndDeletedAtIsNull(created.getId(), "t1").orElseThrow();
    assertThat(saved.getName()).isEqualTo("更新名称");
    assertThat(saved.getEntries()).hasSize(1);
    assertThat(saved.getEntries().getFirst().getId()).isEqualTo(rowId);
    assertThat(saved.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.HIDDEN);
  }

  @Test void unknownUnauthorizedAndDuplicateFieldsAreRejectedBeforeCreation() {
    page("secret.view", false);
    var configuration = new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration("无效", null, List.of(remoteEntry("name")));
    assertThatThrownBy(() -> service.createConfigured(configuration)).hasMessageContaining("未授权");
    page("people.view", false);
    assertThatThrownBy(() -> service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "无效", null, List.of(remoteEntry("secret"))))).hasMessageContaining("未授权");
    assertThatThrownBy(() -> service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "无效", null, List.of(remoteEntry("name"), remoteEntry("name"))))).hasMessageContaining("重复");
    assertThat(session.createQuery("select count(s) from FieldScope s", Long.class).getSingleResult()).isZero();
  }

  @Test void unavailableExistingEntriesSurviveMetadataEditsButCannotChangeAccess() {
    var scope = policy("t1");
    var legacy = remoteEntry("removedField"); legacy.setFieldScopeId(scope.getId()); legacy.setTenantId("t1");
    scope.getEntries().add(legacy); session.persist(legacy); session.flush();
    service.updateConfigured(scope.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "保留旧字段", null, List.of(remoteEntry("removedField"))));
    assertThat(scope.getEntries()).hasSize(1);
    var modified = remoteEntry("removedField"); modified.setAccess(FieldAccessType.VISIBLE);
    assertThatThrownBy(() -> service.updateConfigured(scope.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "不可保存", null, List.of(modified)))).hasMessageContaining("未授权");
    assertThat(scope.getName()).isEqualTo("保留旧字段");
    var foreign = policy("t2");
    assertThatThrownBy(() -> service.updateConfigured(foreign.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "非法跨租户", null, List.of()))).hasMessageContaining("不存在");
  }
}
