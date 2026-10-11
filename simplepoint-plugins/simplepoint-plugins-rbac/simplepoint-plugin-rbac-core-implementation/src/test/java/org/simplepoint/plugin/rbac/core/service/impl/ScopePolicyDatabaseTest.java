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
  FieldScopeCatalog fieldCatalog;
  ResourceAuthorizationVersionService versions = mock(ResourceAuthorizationVersionService.class);

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:policy-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(FieldScope.class).addAnnotatedClass(Resource.class)
        .addAnnotatedClass(User.class).addAnnotatedClass(Role.class).addAnnotatedClass(DataScope.class)
        .addAnnotatedClass(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant.class)
        .addAnnotatedClass(org.simplepoint.plugin.rbac.tenant.api.entity.Organization.class)
        .addAnnotatedClass(RoleResourceGrant.class).addAnnotatedClass(FieldScopeEntry.class).addAnnotatedClass(RoleScopeBinding.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    var repositoryFactory = new JpaRepositoryFactory(session);
    repositoryFactory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    policies = repositoryFactory.getRepository(JpaFieldScopeRepository.class);
    bindings = new JpaRepositoryFactory(session).getRepository(JpaRoleScopeBindingRepository.class);
    fieldCatalog = spy(new FieldScopeCatalog(session));
    service = new FieldScopeServiceImpl(policies, null, versions, fieldCatalog, new PermissionPolicyGuard(session)) {
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
  void referencedDataPolicyCanChangeScopeWithoutRebindingAndRefreshesAuthorization() {
    var factory = new JpaRepositoryFactory(session);
    factory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    var repository = factory.getRepository(org.simplepoint.plugin.rbac.core.base.repository.JpaDataScopeRepository.class);
    var dataService = new DataScopeServiceImpl(repository, null, versions,
        mock(org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository.class), new PermissionPolicyGuard(session)) {
      @Override protected java.util.Set<String> getAllFieldNames(Class<DataScope> type) {
        return new java.util.HashSet<>(List.of("name", "description", "type", "customDeptIds", "resourceCode", "expression"));
      }
    };
    var policy = new DataScope(); policy.setTenantId("t1"); policy.setName("Scope"); policy.setType(DataScopeType.SELF);
    session.persist(policy);
    var grant = new RoleResourceGrant(); grant.setTenantId("t1"); grant.setRoleId("role");
    grant.setResourceCode("organizations.view"); grant.setPagePolicy(true); grant.setDataScopeId(policy.getId());
    session.persist(grant); session.flush();
    var edited = new DataScope(); edited.setId(policy.getId()); edited.setName("My organization and descendants");
    edited.setType(DataScopeType.DEPT_AND_BELOW);
    dataService.modifyById(edited); session.flush(); session.clear();
    assertThat(session.find(DataScope.class, policy.getId()).getType()).isEqualTo(DataScopeType.DEPT_AND_BELOW);
    assertThat(session.find(RoleResourceGrant.class, grant.getId()).getDataScopeId()).isEqualTo(policy.getId());
    verify(versions).refreshTenant("t1");
    assertThatThrownBy(() -> dataService.removeById(policy.getId()))
        .isInstanceOf(org.simplepoint.core.permission.InvalidPermissionPolicyException.class);
  }

  @Test
  void expressionTargetChangesMustRemainCompatibleWithEveryExistingBinding() {
    var policy = new DataScope(); policy.setTenantId("t1"); policy.setName("Scope"); policy.setType(DataScopeType.SELF);
    session.persist(policy);
    var grant = new RoleResourceGrant(); grant.setTenantId("t1"); grant.setRoleId("role");
    grant.setResourceCode("organizations.view"); grant.setPagePolicy(true); grant.setDataScopeId(policy.getId());
    session.persist(grant); session.flush();
    var guard = new PermissionPolicyGuard(session);
    var edited = new DataScope(); edited.setId(policy.getId()); edited.setType(DataScopeType.EXPRESSION);
    edited.setResourceCode("organizations.view");
    guard.validateDataUpdate("t1", edited);
    edited.setResourceCode("users.view");
    assertThatThrownBy(() -> guard.validateDataUpdate("t1", edited))
        .hasMessageContaining("仍用于其他页面");
    edited.setResourceCode("organizations.view");
    var binding = new RoleScopeBinding(); binding.setTenantId("t1"); binding.setRoleId("role"); binding.setDataScopeId(policy.getId());
    session.persist(binding); session.flush();
    assertThatThrownBy(() -> guard.validateDataUpdate("t1", edited)).hasMessageContaining("角色默认范围");
    assertThatThrownBy(() -> guard.validateDataUpdate("t2", edited)).hasMessageContaining("不属于当前工作空间");
    edited.setType(DataScopeType.DEPT);
    guard.validateDataUpdate("t1", edited);
  }

  @Test
  void referencedFieldPolicyCanBeEditedAndInvalidatesExistingContexts() {
    var policy = policy("t1");
    service.replaceEntries(policy.getId(), List.of(entry("name", FieldAccessType.VISIBLE)));
    var grant = new RoleResourceGrant(); grant.setTenantId("t1"); grant.setRoleId("role");
    grant.setResourceCode("orders.view"); grant.setPagePolicy(true); grant.setFieldScopeId(policy.getId());
    session.persist(grant); session.flush();
    service.replaceEntries(policy.getId(), List.of(entry("name", FieldAccessType.HIDDEN)));
    session.flush();
    assertThat(policy.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.HIDDEN);
    verify(versions, times(2)).refreshTenant("t1");
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

  @Test void presentationNamesNeverIncludeRulesFromAnotherTenant() {
    var own = policy("t1"); own.setName("本空间规则");
    var foreign = policy("t2"); foreign.setName("其他空间规则"); session.flush();
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    var presentation = new PermissionPresentationService(session, new FieldScopeCatalog(session)).catalog();
    assertThat(presentation.get("fieldRules")).isEqualTo(Map.of(own.getId(), "本空间规则"));
    assertThat(presentation.get("organizations")).isEqualTo(Map.of());
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> new PermissionPresentationService(session, new FieldScopeCatalog(session)).catalog())
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test void pageCatalogOnlyAdvertisesAuthorizedActivePagesWithFieldMetadata() {
    page("people.view", false); page("secret.view", false); page("disabled.view", true);
    var catalog = service.pageCatalog();
    assertThat(catalog).containsOnlyKeys("people.view");
    assertThat(catalog.get("people.view").title()).isEqualTo("人员");
    assertThat(catalog.get("people.view").models().getFirst().properties()).containsKeys("name", "email");
  }

  @Test void resourceTreeKeepsAncestorsAndPaginatesOnlyAuthorizedPermissionPoints() {
    var root = new Resource(); root.setCode("root"); root.setName("资源目录"); root.setType(ResourceType.GROUP); root.setDisabled(false);
    session.persist(root); session.flush();
    var allowed = new java.util.HashSet<String>();
    for (int i = 0; i < 18; i++) {
      String code = "page" + i + ".view"; page(code, false); allowed.add(code);
      var resource = session.createQuery("from Resource where code=:code", Resource.class).setParameter("code", code).getSingleResult();
      resource.setParentId(root.getId()); resource.setSort(i);
    }
    page("secret.view", false); page("disabled.view", true); allowed.add("disabled.view"); session.flush();
    var context = new AuthorizationContext(); context.setUserId("u1"); context.setResources(allowed);
    context.setAttributes(Map.of("X-Tenant-Id", "t1"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var roots = service.resourceNodes(null, org.springframework.data.domain.PageRequest.of(0, 15));
    assertThat(roots.getContent()).hasSize(1);
    assertThat(roots.getContent().getFirst().id()).isEqualTo(root.getId());
    assertThat(roots.getContent().getFirst().hasChildren()).isTrue();
    assertThat(roots.getContent().getFirst().pageCode()).isNull();
    var first = service.resourceNodes(root.getId(), org.springframework.data.domain.PageRequest.of(0, 15));
    var next = service.resourceNodes(root.getId(), org.springframework.data.domain.PageRequest.of(1, 15));
    assertThat(first.getTotalElements()).isEqualTo(18);
    assertThat(first.getContent()).hasSize(15).allMatch(node -> node.pageCode() != null && !node.hasChildren());
    assertThat(next.getContent()).hasSize(3).noneMatch(first.getContent()::contains);
    assertThat(service.pageFields("page0.view").models()).hasSize(1);
    assertThatThrownBy(() -> service.pageFields("secret.view")).hasMessageContaining("不可用");
    assertThatThrownBy(() -> service.pageFields("disabled.view")).hasMessageContaining("不可用");
    assertThatThrownBy(() -> service.resourceNodes(null, org.springframework.data.domain.PageRequest.of(0, 101))).hasMessageContaining("分页");
  }

  @Test void metadataOnlyUpdatesDoNotReloadEveryPageAndModelAndKeepNewEntriesValidated() {
    page("people.view", false);
    var created = service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "原名称", null, List.of(remoteEntry("name"))));
    session.flush();
    String entryId = created.getEntries().getFirst().getId();
    clearInvocations(fieldCatalog);
    service.updateConfigured(created.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "仅改名称", "仅改描述", List.of(remoteEntry("name"))));
    session.flush();
    assertThat(created.getName()).isEqualTo("仅改名称");
    assertThat(created.getEntries().getFirst().getId()).isEqualTo(entryId);
    verify(fieldCatalog, never()).entries();
    verify(fieldCatalog, never()).pages();
    assertThatThrownBy(() -> service.updateConfigured(created.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "不得保存", null, List.of(remoteEntry("unknown"))))).hasMessageContaining("未授权");
    verify(fieldCatalog, never()).pages();
    verify(fieldCatalog).requireAuthorizedFields(Map.of("org.simplepoint.remote.Person", java.util.Set.of("unknown")));
    assertThat(created.getName()).isEqualTo("仅改名称");
  }
  @Test void configuredEntriesUseOnlyOneAuthorizedValidationAndNeverBuildTheLegacyDirectory() {
    page("people.view", false); clearInvocations(fieldCatalog);
    service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "一次校验", null, List.of(remoteEntry("name"), remoteEntry("email"))));
    session.flush(); verify(fieldCatalog, never()).entries(); verify(fieldCatalog, never()).pages();
    verify(fieldCatalog).requireAuthorizedFields(Map.of("org.simplepoint.remote.Person", java.util.Set.of("name", "email")));
    verify(fieldCatalog, never()).resolveLegacyFields(anyMap());
  }
  @Test void canonicalLocalLegacyFieldDoesNotLoadRemoteMetadataOrFailBecauseOfAnUnrelatedRegistry() {
    var corrupt = new Resource(); corrupt.setCode("unrelated.view"); corrupt.setName("Unrelated"); corrupt.setType(ResourceType.PAGE);
    corrupt.setDisabled(false); corrupt.setPermissionMetadata("malformed unrelated registered metadata"); session.persist(corrupt); session.flush();
    var scope = policy("t1"); clearInvocations(fieldCatalog); factory.getStatistics().clear();
    var proposed = entry("email", FieldAccessType.VISIBLE); proposed.setResource(User.class.getName());
    service.replaceEntries(scope.getId(), List.of(proposed)); session.flush();
    assertThat(scope.getEntries().getFirst().getResource()).isEqualTo(User.class.getName());
    verify(fieldCatalog, never()).entries();
    assertThat(java.util.Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata")).toList()).isEmpty();
  }

  @Test void configuredWritesNeverParseUnrelatedGlobalMetadataOrRevalidateRetainedAndRemovedFields() {
    page("people.view", false);
    var corrupt = new Resource(); corrupt.setCode("unrelated.view"); corrupt.setName("Unrelated"); corrupt.setType(ResourceType.PAGE);
    corrupt.setDisabled(false); corrupt.setPermissionMetadata("malformed unrelated metadata"); session.persist(corrupt); session.flush();
    var created = service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "名称", "描述", List.of(remoteEntry("name"), remoteEntry("email")))); session.flush();
    var removedPage = session.createQuery("from Resource where code='people.view'", Resource.class).getSingleResult();
    removedPage.setDeletedAt(java.time.Instant.now()); session.flush(); clearInvocations(fieldCatalog); factory.getStatistics().clear();
    service.updateConfigured(created.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "保留不可用旧字段", "仅删除邮件", List.of(remoteEntry("name")))); session.flush();
    assertThat(created.getEntries()).hasSize(1);
    assertThat(java.util.Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata")).toList()).isEmpty();
    verify(fieldCatalog, never()).entries(); verify(fieldCatalog, never()).pages(); verify(fieldCatalog, never()).resolveLegacyFields(anyMap());
    var changed = remoteEntry("name"); changed.setAccess(FieldAccessType.HIDDEN);
    assertThatThrownBy(() -> service.updateConfigured(created.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "不可保存", "不可保存", List.of(changed)))).hasMessageContaining("未授权");
    assertThat(created.getName()).isEqualTo("保留不可用旧字段"); assertThat(created.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.MASKED);
  }

  @Test void legacyAliasesAreNormalizedButCanonicalDuplicatesAreRejectedBeforeMutatingRows() {
    page("people.view", false); var policy = policy("t1");
    var alias = remoteEntry(" name "); alias.setResource(" Person ");
    service.replaceEntries(policy.getId(), List.of(alias)); session.flush();
    var stored = policy.getEntries().getFirst(); String rowId = stored.getId();
    assertThat(stored.getResource()).isEqualTo("org.simplepoint.remote.Person"); assertThat(stored.getField()).isEqualTo("name");
    clearInvocations(versions);
    var changed = remoteEntry("name"); changed.setAccess(FieldAccessType.VISIBLE);
    assertThatThrownBy(() -> service.replaceEntries(policy.getId(), List.of(changed, alias))).hasMessageContaining("重复");
    session.flush();
    assertThat(policy.getEntries()).hasSize(1); assertThat(stored.getId()).isEqualTo(rowId); assertThat(stored.getAccess()).isEqualTo(FieldAccessType.MASKED);
    verifyNoInteractions(versions);
  }

  @Test void removingAnEntryIsStillAllowedWhenOnlyChildAccessIsReadonlyAndTheCollectionIsWritable() {
    page("people.view", false);
    var scope = service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "原名称", null, List.of(remoteEntry("name")))); session.flush();
    var context = new AuthorizationContext(); context.setUserId("u1"); context.setResources(java.util.Set.of("people.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "t1")); context.setFieldPermissions(Map.of(FieldScopeEntry.class.getName() + "#access", "VISIBLE"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    factory.getStatistics().clear();
    service.updateConfigured(scope.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "移除默认项", null, List.of())); session.flush();
    assertThat(scope.getEntries()).isEmpty();
    assertThat(java.util.Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata")).toList()).isEmpty();
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

  private void restrictPolicyEntries(String level) {
    var context = new AuthorizationContext();
    context.setUserId("u1"); context.setResources(java.util.Set.of("people.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "t1"));
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#entries", level));
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Test void dedicatedEntryEndpointCannotBypassExplicitReadonlyOrHiddenCollectionPolicies() {
    var scope = policy("t1");
    service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.VISIBLE)));
    session.flush();
    for (var level : List.of("VISIBLE", "MASKED", "HIDDEN")) {
      restrictPolicyEntries(level);
      assertThatThrownBy(() -> service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.HIDDEN))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> service.replaceEntries(scope.getId(), List.of()))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(scope.getEntries()).hasSize(1);
      assertThat(scope.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.VISIBLE);
    }
  }

  @Test void configuredEntryPolicyIsCheckedBeforeChangingMetadataOrCreatingAnyRecord() {
    page("people.view", false);
    var scope = service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "原名称", "原描述", List.of(remoteEntry("name"))));
    session.flush();
    restrictPolicyEntries("VISIBLE");
    var changed = remoteEntry("name"); changed.setAccess(FieldAccessType.HIDDEN);
    assertThatThrownBy(() -> service.updateConfigured(scope.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "不应保存的名称", "不应保存的描述", List.of(changed))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(scope.getName()).isEqualTo("原名称"); assertThat(scope.getDescription()).isEqualTo("原描述");
    long before = session.createQuery("select count(s) from FieldScope s", Long.class).getSingleResult();
    assertThatThrownBy(() -> service.createConfigured(new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "不应创建", null, List.of(remoteEntry("name")))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(session.createQuery("select count(s) from FieldScope s", Long.class).getSingleResult()).isEqualTo(before);
    // Metadata-only changes are still supported when the readable entry set remains exactly unchanged.
    service.updateConfigured(scope.getId(), new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.Configuration(
        "可保存的名称", null, List.of(remoteEntry("name"))));
    assertThat(scope.getName()).isEqualTo("可保存的名称");
    assertThat(scope.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.MASKED);
  }

  @Test void explicitEntryFieldPoliciesAlsoProtectTheDedicatedCollectionEndpoint() {
    var scope = policy("t1"); service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.VISIBLE)));
    var context = new AuthorizationContext(); context.setUserId("u1"); context.setResources(java.util.Set.of("people.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "t1"));
    context.setFieldPermissions(Map.of(FieldScopeEntry.class.getName() + "#access", "VISIBLE"));
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(() -> service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.HIDDEN))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.VISIBLE), entry("description", FieldAccessType.VISIBLE))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    service.replaceEntries(scope.getId(), List.of(entry("name", FieldAccessType.VISIBLE)));
    assertThat(scope.getEntries()).hasSize(1); assertThat(scope.getEntries().getFirst().getAccess()).isEqualTo(FieldAccessType.VISIBLE);
  }
}
