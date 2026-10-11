package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.repository.*;
import org.simplepoint.security.entity.*;

/** Legacy repository adapters execute real SQL; optimized catalog reads must not use whole-entity adapters. */
class TenantResourceCatalogReadTest {
  SessionFactory factory; Session em; TenantResourceCatalogImpl catalog;
  TenantRepository tenants; TenantPackageRelevanceRepository packages; ResourceRepository platform;
  final List<String> statements = new ArrayList<>();

  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:tenant-catalog-read-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;}).build())
        .addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantResource.class).addAnnotatedClass(Resource.class)
        .addAnnotatedClass(TenantPackageRelevance.class).addAnnotatedClass(PackageApplicationRelevance.class)
        .addAnnotatedClass(ApplicationResourceRelevance.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    tenants = mock(TenantRepository.class); packages = mock(TenantPackageRelevanceRepository.class); platform = mock(ResourceRepository.class);
    when(tenants.findById(anyString())).thenAnswer(call -> Optional.ofNullable(em.find(Tenant.class, call.getArgument(0))));
    when(platform.findAllByCodes(anyCollection())).thenAnswer(call -> em.createQuery("select r from Resource r where r.code in :codes", Resource.class)
        .setParameter("codes", call.getArgument(0)).getResultList());
    when(packages.findResourceCodesByTenantId(anyString())).thenAnswer(call -> new LinkedHashSet<>(em.createQuery(
        "select distinct ar.resourceCode from TenantPackageRelevance tp, PackageApplicationRelevance pa, ApplicationResourceRelevance ar"
        + " where tp.tenantId = :tenant and tp.packageCode = pa.packageCode and pa.applicationCode = ar.applicationCode"
        + " and tp.deletedAt is null and pa.deletedAt is null and ar.deletedAt is null", String.class)
        .setParameter("tenant", call.getArgument(0)).getResultList()));
    catalog = new TenantResourceCatalogImpl(em, tenants, packages, platform);
  }
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }
  TenantResource entry(String tenant, String id, String parent, String code, String platformId, String target) {
    var row = new TenantResource(); row.setTenantId(tenant); row.setId(id); row.setParentId(parent); row.setCode(code);
    row.setName("Actual label " + id); row.setType(ResourceType.PAGE); row.setPlatformResourceId(platformId); row.setTargetResourceId(target);
    row.setPath("/actual/" + id); row.setComponent("common/system/Resource"); row.setRouteKind("item"); row.setIcon("FileOutlined");
    row.setDescription("Actual description " + id); row.setSort(1); row.setEnabled(true); row.setGrantable(true);
    row.setLocalModified(true); row.setUpdatedAt(Instant.parse("2026-10-10T00:00:00Z")); em.persist(row); return row;
  }
  Resource resource(String id, String code, boolean publiclyAccessible) {
    var row = new Resource(); row.setId(id); row.setCode(code); row.setName("Private platform name " + id);
    row.setType(ResourceType.PAGE); row.setGrantable(true); row.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    row.setDisabled(false); row.setPublicAccess(publiclyAccessible); row.setPermissionMetadata("Private permission metadata " + id); em.persist(row); return row;
  }
  ApplicationResourceRelevance license(String tenant, String code) {
    var tp = new TenantPackageRelevance(); tp.setTenantId(tenant); tp.setPackageCode("package-" + tenant + "-" + code); em.persist(tp);
    var pa = new PackageApplicationRelevance(); pa.setPackageCode(tp.getPackageCode()); pa.setApplicationCode("app-" + code); em.persist(pa);
    var ar = new ApplicationResourceRelevance(); ar.setApplicationCode(pa.getApplicationCode()); ar.setResourceCode(code); em.persist(ar); return ar;
  }
  void evidence() {em.flush(); em.clear(); statements.clear(); factory.getStatistics().clear(); clearInvocations(tenants, packages, platform);}
  void scalarEvidence(boolean presentation) {
    assertEquals(0, factory.getStatistics().getEntityLoadCount(), "Catalog reads are scalar projections, not complete entities");
    verifyNoInteractions(tenants, packages, platform);
    var membership = Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)");
    for (String sql : statements) {
      var matcher = membership.matcher(sql); while (matcher.find()) assertTrue(matcher.group(1).chars().filter(c -> c == '?').count() <= 100, sql);
      assertFalse(sql.toLowerCase(Locale.ROOT).replace("_", "").contains("permissionmetadata"), sql);
      if (!presentation) assertFalse(sql.contains(".name") || sql.contains(".description") || sql.contains(".icon"), "Authorization must not read presentation fields: " + sql);
    }
  }

  @Test void completeActiveProjectionPreservesAllRowsAndFieldsButRejectsBrokenAncestryWithoutEntityLoads() {
    var root = entry("tenant-a", "root", null, "root", null, null); root.setType(ResourceType.GROUP); root.setGrantable(false);
    var expected = new HashSet<String>(); expected.add("root");
    for (int i = 0; i < 521; i++) {String id = "entry-" + String.format("%03d", i); expected.add(id); entry("tenant-a", id, "root", id, id, null);
      entry("tenant-b", id, null, "foreign-" + id, id, null);}
    entry("tenant-a", "disabled", null, "disabled", null, null).setEnabled(false);
    entry("tenant-a", "blocked-child", "disabled", "blocked-child", null, null);
    entry("tenant-a", "orphan", "absent", "orphan", null, null);
    entry("tenant-a", "cycle-a", "cycle-b", "cycle-a", null, null); entry("tenant-a", "cycle-b", "cycle-a", "cycle-b", null, null);
    evidence(); var actual = catalog.activeResources("tenant-a");
    assertEquals(expected, actual.stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet()));
    var last = actual.stream().filter(row -> row.getId().equals("entry-520")).findFirst().orElseThrow();
    assertEquals("Actual description entry-520", last.getDescription()); assertEquals("common/system/Resource", last.getComponent());
    assertTrue(last.isLocalModified()); assertFalse(last.isManualDisabled()); assertEquals(Instant.parse("2026-10-10T00:00:00Z"), last.getUpdatedAt());
    assertTrue(factory.getStatistics().getQueryExecutionCount() <= 16, "Complete hierarchy is not a query per node"); scalarEvidence(true);
  }

  @Test void explicitAuthorizationPreservesMoreThanFiveHundredAliasesWithoutPresentationOrWholeLicenseReads() {
    var root = entry("tenant-a", "root", null, "root", null, null); root.setType(ResourceType.GROUP); root.setGrantable(false);
    var requested = new LinkedHashSet<String>(); var expected = new LinkedHashSet<String>();
    for (int i = 0; i < 521; i++) {String suffix = String.format("%03d", i), target = "target-" + suffix, code = "platform-" + suffix, alias = "alias-" + suffix;
      resource(target, code, false); license("tenant-a", code); entry("tenant-a", target, "root", code, target, null);
      entry("tenant-a", alias, "root", alias, null, target); requested.add(alias); expected.add(code);}
    evidence(); assertNull(AuthorizationContextHolder.getContext(), "This is a legitimate authorization-context construction helper");
    assertEquals(requested, catalog.grantableCodes("tenant-a", requested)); assertEquals(expected, catalog.effectivePlatformCodes("tenant-a", requested)); scalarEvidence(false);
  }

  @Test void actualLicenseRevocationIsEffectiveBeforeSnapshotSynchronization() {
    resource("target", "platform.page", false); var subscription = license("tenant-a", "platform.page");
    entry("tenant-a", "target", null, "platform.page", "target", null); entry("tenant-a", "alias", null, "alias", null, "target"); evidence();
    assertEquals(Set.of("alias"), catalog.grantableCodes("tenant-a", Set.of("alias")));
    var relation = em.find(ApplicationResourceRelevance.class, subscription.getId()); relation.setDeletedAt(Instant.now()); evidence();
    assertTrue(catalog.grantableCodes("tenant-a", Set.of("alias")).isEmpty()); assertTrue(catalog.effectivePlatformCodes("tenant-a", Set.of("alias")).isEmpty());
    scalarEvidence(false);
    assertEquals(Boolean.TRUE, em.createQuery("select r.enabled from TenantResource r where r.tenantId = :tenant and r.id = :id", Boolean.class)
        .setParameter("tenant", "tenant-a").setParameter("id", "alias").getSingleResult(), "No synchronization or mutation is necessary to revoke authorization");
  }

  @Test void disabledCyclesMissingTargetsAndOtherNamespacesCannotGrantAliases() {
    for (String state : List.of("good", "public", "foreign-license", "disabled-platform", "deleted-platform", "foreign-scope", "blocked", "cycle", "nongrantable")) {
      var source = resource(state, "platform." + state, state.equals("public"));
      if (!state.equals("public")) license(state.equals("foreign-license") ? "tenant-b" : "tenant-a", source.getCode());
      if (state.equals("disabled-platform")) source.setDisabled(true);
      if (state.equals("deleted-platform")) source.setDeletedAt(Instant.now());
      if (state.equals("foreign-scope")) source.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
      String parent = state.equals("blocked") ? "disabled-parent" : state.equals("cycle") ? "cycle-a" : null;
      var target = entry("tenant-a", state, parent, source.getCode(), state, null); if (state.equals("nongrantable")) target.setGrantable(false);
      entry("tenant-a", "alias-" + state, null, "alias-" + state, null, state);
    }
    entry("tenant-a", "disabled-parent", null, "disabled-parent", null, null).setEnabled(false);
    entry("tenant-a", "cycle-a", "cycle-b", "cycle-a", null, null); entry("tenant-a", "cycle-b", "cycle-a", "cycle-b", null, null);
    entry("tenant-b", "foreign-only", null, "foreign-only", "good", null); entry("tenant-a", "missing-alias", null, "missing-alias", null, "foreign-only");
    evidence(); var requested = new LinkedHashSet<>(List.of("alias-good", "alias-public", "alias-foreign-license", "alias-disabled-platform", "alias-deleted-platform", "alias-foreign-scope", "alias-blocked", "alias-cycle", "alias-nongrantable", "missing-alias", "' OR 1=1 --"));
    assertEquals(Set.of("alias-good", "alias-public"), catalog.grantableCodes("tenant-a", requested));
    assertEquals(Set.of("platform.good", "platform.public"), catalog.effectivePlatformCodes("tenant-a", requested)); scalarEvidence(false);
  }

  @Test void nativeLegacyScopeConversionAndNonDisplayCodesDoNotDependOnAnInitializedRequestContext() {
    resource("target", "platform.page", true); entry("tenant-a", "target", null, "platform.page", "target", null);
    em.flush(); em.createNativeQuery("update simpoint_ac_resources set scope_types = :scopes where id = :id")
        .setParameter("scopes", "PERSONAL, TENANT").setParameter("id", "target").executeUpdate(); evidence();
    var context = new AuthorizationContext(); context.setScopeType(AuthorizationScopeType.PLATFORM); context.setIsAdministrator(true);
    context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#code", "HIDDEN", Resource.class.getName() + "#code", "MASKED"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertEquals(Set.of("platform.page"), catalog.grantableCodes("tenant-a", Set.of("platform.page")));
    assertEquals(Set.of("platform.page"), catalog.effectivePlatformCodes("tenant-a", Set.of("platform.page"))); scalarEvidence(false);
  }

  @Test void longCompleteAncestryIsValidatedOnceInsteadOfLoadingEachNode() {
    String parent = null; var expected = new HashSet<String>();
    for (int i = 0; i < 521; i++) {String id = "chain-" + String.format("%03d", i); entry("tenant-a", id, parent, id, null, null); expected.add(id); parent = id;}
    evidence(); assertEquals(expected, catalog.activeResources("tenant-a").stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet()));
    assertTrue(factory.getStatistics().getQueryExecutionCount() <= 16); scalarEvidence(true);
  }

  @Test void modeIsAnActualScalarAndEmptyNamespaceInputsExecuteNoSql() {
    var tenant = new Tenant(); tenant.setId("tenant-a"); tenant.setName("Workspace"); tenant.setOwnerId("owner"); tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L); tenant.setResourceMode(TenantResourceMode.CUSTOM); em.persist(tenant); evidence();
    assertEquals(TenantResourceMode.CUSTOM, catalog.mode("tenant-a")); assertEquals(TenantResourceMode.PLATFORM, catalog.mode("missing")); scalarEvidence(false);
    statements.clear(); assertEquals(TenantResourceMode.PLATFORM, catalog.mode(" ")); assertTrue(catalog.activeResources(" ").isEmpty());
    assertTrue(catalog.grantableCodes("tenant-a", List.of()).isEmpty()); assertTrue(catalog.effectivePlatformCodes(" ", Set.of("code")).isEmpty()); assertTrue(statements.isEmpty());
  }

  @Test void selectedNavigationPreservesFiveHundredAliasesAndSuppressesTheirBackingPageWithoutReadingTheWholeCatalog() {
    var root = entry("tenant-a", "root", null, "root", null, null); root.setType(ResourceType.GROUP); root.setGrantable(false);
    var held = new LinkedHashSet<String>(); var expected = new HashSet<String>(); expected.add("root");
    for (int i = 0; i < 521; i++) {String suffix = String.format("%03d", i), id = "alias-" + suffix;
      entry("tenant-a", "target-" + suffix, "root", "platform-" + suffix, "target-" + suffix, null);
      entry("tenant-a", id, "root", id, null, "target-" + suffix); held.add(id); expected.add(id);}
    held.add("platform-000"); entry("tenant-a", "unheld", null, "unheld", null, null); evidence();
    assertEquals(expected, catalog.navigationResources("tenant-a", held).stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet()));
    assertTrue(statements.stream().filter(sql -> sql.contains("simpoint_saas_tenant_resources")).allMatch(sql -> sql.contains(" in ")), "Selected navigation does not scan the full catalog");
    scalarEvidence(true);
  }

  @Test void narrowAuthorizationReadsOnlySelectedStructureAndEmptyNavigationNeverLoadsTheDirectory() {
    resource("target", "platform.page", true); entry("tenant-a", "target", null, "platform.page", "target", null);
    entry("tenant-a", "alias", null, "alias", null, "target");
    for (int i = 0; i < 521; i++) entry("tenant-a", "unrelated-" + i, null, "unrelated-" + i, null, null);
    evidence(); assertEquals(Set.of("alias"), catalog.grantableCodes("tenant-a", Set.of("alias")));
    assertTrue(factory.getStatistics().getQueryExecutionCount() <= 3); assertTrue(statements.stream().allMatch(sql -> sql.contains(" in ")));
    scalarEvidence(false); statements.clear(); assertTrue(catalog.navigationResources("tenant-a", Set.of()).isEmpty()); assertTrue(statements.isEmpty());
  }
}
