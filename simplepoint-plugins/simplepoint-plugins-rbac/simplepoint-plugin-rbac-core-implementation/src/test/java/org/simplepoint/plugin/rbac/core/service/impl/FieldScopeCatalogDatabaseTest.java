package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.permission.PermissionModelDescriptor;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.springframework.data.domain.PageRequest;

/** Real SQL shape and authorization boundaries, not a pre-paged browser fixture. */
class FieldScopeCatalogDatabaseTest {
  private SessionFactory factory;
  private Session session;
  private FieldScopeCatalog catalog;
  private final List<String> statements = new ArrayList<>();

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:field-tree-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", "true")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) statement -> {
          statements.add(statement); return statement;
        }).build();
    factory = new MetadataSources(registry).addAnnotatedClass(Resource.class).addAnnotatedClass(TenantResource.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction(); catalog = new FieldScopeCatalog(session);
    var context = new AuthorizationContext(); context.setUserId("actor"); context.setResources(new HashSet<>());
    context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  private Resource resource(String id, ResourceType type, String parent, String metadata) {
    var resource = new Resource(); resource.setId(id); resource.setCode(id); resource.setName(id); resource.setSort(0);
    resource.setType(type); resource.setParentId(parent); resource.setDisabled(false); resource.setPermissionMetadata(metadata);
    session.persist(resource); return resource;
  }

  private String metadata(boolean fields, boolean properties) {
    var field = new PermissionModelDescriptor.Field("java.lang.String", "Name", List.of("equals"), List.of());
    return new PermissionPageDescriptor(List.of(new PermissionModelDescriptor("remote.Person", true, fields,
        "id", null, properties ? Map.of("name", field) : Map.of()))).toJson();
  }

  private String modelMetadata(String model, String... names) {
    var properties = new java.util.LinkedHashMap<String, PermissionModelDescriptor.Field>();
    for (var name : names) properties.put(name, new PermissionModelDescriptor.Field("java.lang.String", name, List.of("equals"), List.of()));
    return new PermissionPageDescriptor(List.of(new PermissionModelDescriptor(model, true, true, "id", null, properties))).toJson();
  }

  private void resetEvidence() {session.flush(); session.clear(); statements.clear(); factory.getStatistics().clear();}
  private long metadataRows() {
    return java.util.Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata"))
        .mapToLong(query -> factory.getStatistics().getQueryStatistics(query).getExecutionRowCount()).sum();
  }

  private void assertBoundedCatalogSql() {
    for (var sql : statements) if (sql.startsWith("select")) {
      // Inspect actual expanded SQL, not a mocked Pageable or an unexpanded JPQL parameter.
      var clauses = java.util.regex.Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)").matcher(sql);
      while (clauses.find()) assertThat(clauses.group(1).chars().filter(character -> character == '?').count())
          .as("Every expanded membership bind must be bounded: %s", sql).isLessThanOrEqualTo(100);
    }
  }

  @Test void completeLegacyRegistryIsKeysetPagedWithoutChangingItsGlobalRegistrationSemantics() {
    for (int index = 0; index < 521; index++) resource("page-" + String.format("%03d", index), ResourceType.PAGE, null,
        modelMetadata("remote.Model" + index, "field"));
    resource("disabled", ResourceType.PAGE, null, modelMetadata("remote.Disabled", "field")).setDisabled(true);
    resource("group", ResourceType.GROUP, null, modelMetadata("remote.Group", "field"));
    resource("deleted", ResourceType.PAGE, null, "malformed deleted metadata").setDeletedAt(java.time.Instant.now());
    resource("blank", ResourceType.PAGE, null, " ");
    resource("no-fields", ResourceType.PAGE, null, metadata(false, true));
    resetEvidence();
    var entries = catalog.entries();
    assertThat(entries).containsKeys("remote.Model520", "remote.Disabled", "remote.Group", Resource.class.getName());
    assertThat(entries.keySet().stream().filter(name -> name.startsWith("remote.")).count()).isEqualTo(523);
    assertThat(entries).doesNotContainKey("remote.Person");
    assertThat(metadataRows()).isEqualTo(525); assertThat(factory.getStatistics().getQueryExecutionCount()).isEqualTo(6);
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements).hasSize(6).allSatisfy(sql -> assertThat(sql).contains("order by", "fetch first ? rows only").doesNotContain("offset"));
    assertThat(statements.stream().filter(sql -> sql.contains("code>?")).count()).isEqualTo(5);
  }

  @Test void canonicalLocalLegacyFieldsRequireNoMetadataReadButRemoteExtensionsAreStillValid() {
    resource("broken", ResourceType.PAGE, null, "malformed unrelated metadata"); resetEvidence();
    assertThat(catalog.resolveLegacyFields(Map.of(Resource.class.getName(), java.util.Set.of("name", "code"))))
        .isEqualTo(Map.of(Resource.class.getName(), Resource.class.getName()));
    assertThat(statements).isEmpty();
    var broken = session.find(Resource.class, "broken"); broken.setPermissionMetadata(modelMetadata(Resource.class.getName(), "pluginField"));
    resetEvidence();
    assertThat(catalog.resolveLegacyFields(Map.of(Resource.class.getName(), java.util.Set.of("name", "pluginField"))))
        .isEqualTo(Map.of(Resource.class.getName(), Resource.class.getName()));
    assertThat(metadataRows()).isEqualTo(1);
  }

  @Test void simpleAliasesRemainGloballyAmbiguousEvenForALateEmptyModelAndUnrelatedPageGrants() {
    resource("first", ResourceType.PAGE, null, modelMetadata("remote.Person", "name"));
    for (int index = 0; index < 521; index++) resource("middle-" + index, ResourceType.PAGE, null, modelMetadata("remote.Other" + index, "field"));
    resource("z-last", ResourceType.GROUP, null, modelMetadata("other.Person")).setDisabled(true); resetEvidence();
    assertThatThrownBy(() -> catalog.resolveLegacyFields(Map.of("Person", java.util.Set.of("name"))))
        .hasMessageContaining("有歧义");
    assertThat(metadataRows()).isEqualTo(523); assertThat(factory.getStatistics().getQueryExecutionCount()).isEqualTo(6);
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
  }

  @Test void exactSimpleModelKeysStillOverrideAmbiguousAliasesAndIncompleteExactFieldsDoNotFallBack() {
    resource("a", ResourceType.PAGE, null, modelMetadata("remote.Person", "name"));
    resource("b", ResourceType.PAGE, null, modelMetadata("other.Person", "name"));
    resource("z-exact", ResourceType.PAGE, null, modelMetadata("Person", "name")); resetEvidence();
    assertThat(catalog.resolveLegacyFields(Map.of("Person", java.util.Set.of("name")))).isEqualTo(Map.of("Person", "Person"));
    assertThatThrownBy(() -> catalog.resolveLegacyFields(Map.of("Person", java.util.Set.of("email"))))
        .hasMessageContaining("字段不存在");
  }

  @Test void canonicalRemoteValidationMergesRequestedFieldsAcrossRegistrationsAndStopsAfterCompletion() {
    resource("a", ResourceType.PAGE, null, modelMetadata("remote.Person", "name"));
    resource("b", ResourceType.GROUP, null, modelMetadata("remote.Person", "email"));
    resource("z-malformed", ResourceType.PAGE, null, "malformed later metadata"); resetEvidence();
    assertThat(catalog.resolveLegacyFields(Map.of("remote.Person", java.util.Set.of("name", "email"))))
        .isEqualTo(Map.of("remote.Person", "remote.Person"));
    assertThat(factory.getStatistics().getQueryExecutionCount()).isEqualTo(1);
    assertThatThrownBy(catalog::entries).isInstanceOf(IllegalStateException.class);
  }

  @Test void authorizedValidationUnionsTheSameCanonicalModelAcrossAllGrantedBatchesAndRejectsAliases() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    // Preserve this genuine grant order to place the second field in a later SQL batch.
    var ordered = new java.util.LinkedHashSet<String>();
    for (int index = 0; index < 521; index++) {
      String code = "page-" + String.format("%03d", index); ordered.add(code);
      resource(code, ResourceType.PAGE, null, index == 0 ? modelMetadata("remote.Person", "name")
          : index == 520 ? modelMetadata("remote.Person", "email") : modelMetadata("remote.Other" + index, "field"));
    }
    context = new AuthorizationContext(); context.setUserId("actor"); context.setResources(ordered);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant")); RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    resource("ungranted", ResourceType.PAGE, null, "malformed unrelated metadata"); resetEvidence();
    catalog.requireAuthorizedFields(Map.of("remote.Person", java.util.Set.of("name", "email")));
    assertThat(metadataRows()).isEqualTo(521); assertThat(factory.getStatistics().getQueryExecutionCount()).isEqualTo(6);
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements).hasSize(6).allSatisfy(sql -> assertThat(sql).contains("code in", "fetch first ? rows only").doesNotContain("resource_alias"));
    assertThatThrownBy(() -> catalog.requireAuthorizedFields(Map.of("Person", java.util.Set.of("name")))).hasMessageContaining("未授权");
  }

  @Test void authorizedValidationStopsBeforeLaterBatchesButEmptyGrantsNeverInheritTheLegacyRegistry() {
    var grants = new java.util.LinkedHashSet<String>();
    for (int index = 0; index < 201; index++) {
      var code = "page-" + index; grants.add(code);
      resource(code, ResourceType.PAGE, null, index < 100 ? modelMetadata("remote.Person", "name") : "malformed later granted metadata");
    }
    var context = new AuthorizationContext(); context.setUserId("actor"); context.setResources(grants);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context); resetEvidence();
    catalog.requireAuthorizedFields(Map.of("remote.Person", java.util.Set.of("name")));
    assertThat(factory.getStatistics().getQueryExecutionCount()).isEqualTo(1); assertThat(metadataRows()).isEqualTo(100);
    context = new AuthorizationContext(); context.setUserId("actor"); context.setIsAdministrator(true); context.setResources(java.util.Set.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context); resetEvidence();
    assertThatThrownBy(() -> catalog.requireAuthorizedFields(Map.of("remote.Person", java.util.Set.of("name")))).hasMessageContaining("未授权");
    assertThat(statements).isEmpty();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> catalog.requireAuthorizedFields(Map.of("remote.Person", java.util.Set.of("name"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private TenantResource tenantResource(String tenant, String id, String code, String parent, ResourceType type) {
    var resource = new TenantResource(); resource.setTenantId(tenant); resource.setId(id); resource.setCode(code);
    resource.setName(tenant + ":" + id); resource.setParentId(parent); resource.setType(type); resource.setSort(0);
    session.persist(resource); return resource;
  }

  @Test
  void treeUsesBoundedMetadataBatchesAndSqlPagesWithoutLoadingResourceEntitiesOrUnrelatedMetadata() {
    resource("root", ResourceType.GROUP, null, null);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    for (int number = 0; number < 521; number++) {
      String id = "page-" + String.format("%03d", number);
      resource(id, ResourceType.PAGE, "root", metadata(true, true)); context.getResources().add(id);
    }
    resource("unrelated", ResourceType.PAGE, "root", "unparseable unrelated metadata");
    resource("no-fields", ResourceType.PAGE, "root", metadata(false, true)); context.getResources().add("no-fields");
    resource("empty-fields", ResourceType.PAGE, "root", metadata(true, false)); context.getResources().add("empty-fields");
    resource("disabled", ResourceType.PAGE, "root", "unparseable disabled metadata").setDisabled(true);
    context.getResources().add("disabled");
    resource("deleted", ResourceType.PAGE, "root", "unparseable deleted metadata").setDeletedAt(java.time.Instant.now());
    context.getResources().add("deleted");
    resource("inactive-parent", ResourceType.GROUP, null, null).setDisabled(true);
    resource("orphan", ResourceType.PAGE, "inactive-parent", metadata(true, true)); context.getResources().add("orphan");
    session.flush(); session.clear(); statements.clear(); factory.getStatistics().clear();
    var roots = catalog.nodes(null, PageRequest.of(0, 20));
    assertThat(roots.getTotalElements()).isEqualTo(2);
    assertThat(roots.getContent()).extracting(node -> node.id()).containsExactly("orphan", "root");
    assertThat(roots.getContent().getLast().hasChildren()).isTrue();
    assertThat(roots.getContent().getFirst().pageCode()).isEqualTo("orphan");
    var first = catalog.nodes("root", PageRequest.of(0, 20));
    assertThat(first.getTotalElements()).isEqualTo(521); assertThat(first.getContent()).hasSize(20);
    assertThat(first.getContent().getFirst().id()).isEqualTo("page-000");
    assertThat(first.getContent()).allSatisfy(node -> {assertThat(node.hasChildren()).isFalse(); assertThat(node.pageCode()).isEqualTo(node.id());});
    var last = catalog.nodes("root", PageRequest.of(26, 20));
    assertThat(last.getContent()).extracting(node -> node.id()).containsExactly("page-520");
    assertThat(catalog.nodes("root", PageRequest.of(27, 20)).getContent()).isEmpty();
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements.stream().filter(sql -> sql.startsWith("select") && sql.contains("permission_metadata")).toList())
        .isNotEmpty().allSatisfy(sql -> assertThat(sql).contains("fetch first ? rows only"));
    assertThat(statements.stream().filter(sql -> sql.startsWith("select") && sql.contains("resource_alias")).toList())
        .hasSize(3).allSatisfy(sql -> assertThat(sql).contains("fetch first ? rows only").doesNotContain("permission_metadata"));
    assertBoundedCatalogSql();
    context.getResources().clear(); context.getResources().addAll(List.of("no-fields", "empty-fields"));
    assertThat(catalog.nodes(null, PageRequest.of(0, 20))).isEmpty();
  }

  @Test
  void customTreeUsesTenantQualifiedSqlPagesAndRejectsInactiveMissingAndCyclicAncestors() throws Exception {
    var tenantCatalog = mock(TenantResourceCatalog.class);
    when(tenantCatalog.mode("tenant")).thenReturn(TenantResourceMode.CUSTOM);
    var field = FieldScopeCatalog.class.getDeclaredField("tenantResources"); field.setAccessible(true); field.set(catalog, tenantCatalog);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    tenantResource("tenant", "root", "tenant-root", null, ResourceType.GROUP);
    tenantResource("foreign", "root", "foreign-root", null, ResourceType.GROUP);
    for (int number = 0; number < 521; number++) {
      String code = "canonical-" + String.format("%03d", number), id = "node-" + String.format("%03d", number);
      resource(code, ResourceType.PAGE, null, metadata(true, true)); context.getResources().add(code);
      tenantResource("tenant", id, code, "root", ResourceType.PAGE);
      tenantResource("foreign", id, code, "root", ResourceType.PAGE);
    }
    tenantResource("tenant", "inactive", "inactive-directory", null, ResourceType.GROUP).setEnabled(false);
    tenantResource("tenant", "cycle-group", "cycle-directory", "cycle-page", ResourceType.GROUP);
    for (String code : List.of("inactive-page", "missing-page", "cycle-page")) {
      resource(code, ResourceType.PAGE, null, metadata(true, true)); context.getResources().add(code);
      tenantResource("tenant", code, code, code.equals("inactive-page") ? "inactive"
          : code.equals("missing-page") ? "missing" : "cycle-group", ResourceType.PAGE);
    }
    resource("unauthorized", ResourceType.PAGE, null, "unparseable unrelated metadata");
    tenantResource("tenant", "unauthorized", "unauthorized", "root", ResourceType.PAGE);
    session.flush(); session.clear(); statements.clear(); factory.getStatistics().clear();
    var roots = catalog.nodes(null, PageRequest.of(0, 20));
    assertThat(roots.getContent()).extracting(node -> node.id()).containsExactly("root");
    assertThat(roots.getContent().getFirst().title()).isEqualTo("tenant:root");
    assertThat(roots.getContent().getFirst().hasChildren()).isTrue();
    var first = catalog.nodes("root", PageRequest.of(0, 20));
    assertThat(first.getTotalElements()).isEqualTo(521); assertThat(first.getContent()).hasSize(20);
    assertThat(first.getContent()).allSatisfy(node -> assertThat(node.title()).startsWith("tenant:").doesNotContain("foreign"));
    var last = catalog.nodes("root", PageRequest.of(26, 20));
    assertThat(last.getContent()).extracting(node -> node.id()).containsExactly("node-520");
    assertThat(last.getContent().getFirst().pageCode()).isEqualTo("canonical-520");
    assertThat(catalog.nodes("inactive", PageRequest.of(0, 20))).isEmpty();
    assertThat(catalog.nodes("missing", PageRequest.of(0, 20))).isEmpty();
    assertThat(catalog.nodes("cycle-group", PageRequest.of(0, 20))).isEmpty();
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    verify(tenantCatalog, never()).activeResources("tenant");
    assertBoundedCatalogSql();
    assertThat(statements.stream().filter(sql -> sql.startsWith("select") && sql.contains("simpoint_saas_tenant_resources")
        && !sql.startsWith("select count")).toList()).isNotEmpty()
        .allSatisfy(sql -> assertThat(sql).contains("tenant_id=?", "fetch first ? rows only"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN", "VISIBLE"})
  void tenantTitlesAndIconsUseTheTenantSourceNotThePlatformFieldNamespace(String access) throws Exception {
    resource("canonical", ResourceType.PAGE, null, metadata(true, true));
    org.simplepoint.core.AuthorizationContextHolder.getContext().getResources().add("canonical");
    var own = tenantResource("tenant", "node", "canonical", null, ResourceType.PAGE); own.setName("Private tenant name"); own.setIcon("Private tenant icon");
    tenantResource("foreign", "node", "canonical", null, ResourceType.PAGE).setName("Foreign secret");
    var tenantCatalog = mock(TenantResourceCatalog.class); when(tenantCatalog.mode("tenant")).thenReturn(TenantResourceMode.CUSTOM);
    var field = FieldScopeCatalog.class.getDeclaredField("tenantResources"); field.setAccessible(true); field.set(catalog, tenantCatalog);
    org.simplepoint.core.AuthorizationContextHolder.getContext().setFieldPermissions(Map.of(Resource.class.getName() + "#name", "HIDDEN",
        Resource.class.getName() + "#icon", "HIDDEN", TenantResource.class.getName() + "#name", access, TenantResource.class.getName() + "#icon", access));
    resetEvidence();
    var node = catalog.nodes(null, PageRequest.of(0, 20)).getContent().getFirst();
    assertThat(node.title()).isEqualTo(access.equals("MASKED") ? "***" : access.equals("HIDDEN") ? "" : "Private tenant name");
    assertThat(node.icon()).isEqualTo(access.equals("MASKED") ? "***" : access.equals("HIDDEN") ? null : "Private tenant icon");
    assertThat(node.pageCode()).isEqualTo("canonical"); assertThat(catalog.pages("canonical").get("canonical").title()).isEmpty();
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero(); assertBoundedCatalogSql();
  }

  @Test
  void cyclesStayUnreachableAndMissingAuthorizationCannotLoadTheCatalog() {
    resource("a", ResourceType.PAGE, "b", metadata(true, true));
    resource("b", ResourceType.GROUP, "a", null);
    org.simplepoint.core.AuthorizationContextHolder.getContext().getResources().add("a");
    session.flush(); session.clear();
    assertThat(catalog.pages("a")).containsKey("a");
    assertThat(catalog.nodes(null, PageRequest.of(0, 20))).isEmpty();
    assertThat(catalog.nodes("b", PageRequest.of(0, 20)).getContent()).extracting(node -> node.id()).containsExactly("a");
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> catalog.nodes(null, PageRequest.of(0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void interleavedUnrelatedNodesAndNullSortKeysRetainDatabaseOrderingBeyondFiveHundred(boolean nullsHigh) {
    if (nullsHigh) session.createNativeMutationQuery("SET DEFAULT_NULL_ORDERING HIGH").executeUpdate();
    resource("root", ResourceType.GROUP, null, null);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var expectedIds = new java.util.HashSet<String>();
    for (int index = 0; index < 521; index++) {
      String id = "node-" + String.format("%03d", index); expectedIds.add(id); context.getResources().add(id);
      resource(id, ResourceType.PAGE, "root", metadata(true, true)).setSort(index % 7 == 0 ? null : index % 5);
      resource("unrelated-" + String.format("%03d", index), ResourceType.PAGE, "root", "malformed unrelated metadata").setSort(index % 4);
    }
    resetEvidence();
    // The real database defines both collation and null ordering; do not replace it with Java's String comparator.
    var expected = session.createQuery("select r.id from Resource r where r.id in :ids order by r.sort,r.id", String.class)
        .setParameter("ids", expectedIds).getResultList(); statements.clear(); factory.getStatistics().clear();
    assertThat(Integer.parseInt(expected.getFirst().substring(5)) % 7 == 0).isEqualTo(!nullsHigh);
    for (int page : List.of(0, 1, 9, 25, 26, 27)) {
      var actual = catalog.nodes("root", PageRequest.of(page, 20));
      assertThat(actual.getTotalElements()).isEqualTo(521);
      assertThat(actual.getContent()).extracting(node -> node.id()).containsExactlyElementsOf(expected.subList(Math.min(page * 20, expected.size()), Math.min((page + 1) * 20, expected.size())));
    }
    assertBoundedCatalogSql(); assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements.stream().filter(sql -> sql.startsWith("select") && !sql.contains("permission_metadata") && !sql.contains("resource_alias")))
        .allSatisfy(sql -> assertThat(sql).contains("fetch first ? rows only").doesNotContain("offset"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void largeRootLevelsRetainNativeGlobalOrderingAndTenantIsolationWithBoundedBinds(boolean custom) throws Exception {
    var tenantCatalog = mock(TenantResourceCatalog.class); when(tenantCatalog.mode("tenant")).thenReturn(TenantResourceMode.CUSTOM);
    if (custom) {var field = FieldScopeCatalog.class.getDeclaredField("tenantResources"); field.setAccessible(true); field.set(catalog, tenantCatalog);}
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var ids = new java.util.HashSet<String>();
    for (int index = 0; index < 521; index++) {
      String code = "page-" + String.format("%03d", index), id = custom ? "node-" + String.format("%03d", index) : code;
      ids.add(id); context.getResources().add(code); resource(code, ResourceType.PAGE, null, metadata(true, true)).setSort(index % 3);
      resource("ungranted-" + index, ResourceType.PAGE, null, "malformed unrelated metadata").setSort(index % 5);
      if (custom) {
        tenantResource("tenant", id, code, null, ResourceType.PAGE).setSort(index % 3);
        tenantResource("foreign", id, code, null, ResourceType.PAGE).setSort(-100);
        tenantResource("tenant", "ungranted-node-" + index, "ungranted-" + index, null, ResourceType.PAGE);
      }
    }
    resetEvidence();
    var query = session.createQuery("select r.id from " + (custom ? "TenantResource" : "Resource") + " r where r.id in :ids"
        + (custom ? " and r.tenantId=:tenant" : "") + " order by r.sort,r.id", String.class).setParameter("ids", ids);
    if (custom) query.setParameter("tenant", "tenant");
    var expected = query.getResultList(); statements.clear(); factory.getStatistics().clear();
    for (int page : List.of(0, 9, 26, 27)) {
      var actual = catalog.nodes(null, PageRequest.of(page, 20));
      assertThat(actual.getTotalElements()).isEqualTo(521);
      assertThat(actual.getContent()).extracting(node -> node.id()).containsExactlyElementsOf(expected.subList(Math.min(page * 20, expected.size()), Math.min((page + 1) * 20, expected.size())));
      if (custom) assertThat(actual.getContent()).allSatisfy(node -> assertThat(node.title()).startsWith("tenant:"));
    }
    assertBoundedCatalogSql(); assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    verify(tenantCatalog, never()).activeResources("tenant");
  }

  @Test void blankCallerIdentityCannotLoadEvenExplicitlyGrantedMetadata() {
    resource("page", ResourceType.PAGE, null, metadata(true, true)); resetEvidence();
    var context = new AuthorizationContext(); context.setUserId(" "); context.setResources(java.util.Set.of("page"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(() -> catalog.nodes(null, PageRequest.of(0, 20))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(statements).isEmpty();
  }
}
