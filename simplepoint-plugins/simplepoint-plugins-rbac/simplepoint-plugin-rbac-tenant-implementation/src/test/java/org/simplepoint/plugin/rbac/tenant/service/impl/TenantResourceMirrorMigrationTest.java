package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.security.entity.ResourceScopeType;

/** Verifies real legacy rows through the exact PostgreSQL migration manifest. */
@EnabledIfEnvironmentVariable(named = "SP_TENANT_PACKAGE_TEST_POSTGRES_URL", matches = ".+")
class TenantResourceMirrorMigrationTest {
  @Test
  void hostAcquisitionMigrationPreservesExistingReceiptsAndDoesNotIssueCredits() throws Exception {
    try (var database =
        new TenantEntitlementDatabase(
            (connection, migration) -> {
              if (migration.equals("V17__tenant_legacy_host_acquisition.sql")) {
                try (var statement = connection.createStatement()) {
                  statement.execute(
                      """
                      insert into simpoint_saas_tenant_package_instances
                        (id,tenant_id,package_code,package_name,state,acquisition,assigned_at,assigned_by,activated_at,activated_by,adopted_at)
                      values ('legacy','tenant','old','Old','ACTIVE','LEGACY_ROLE',
                        '2026-01-01T01:00:00Z','installer','2026-01-01T01:00:00Z','installer','2026-01-01T01:00:00Z');
                      """);
                }
              }
            })) {
      var factory =
          database.create(
              TenantPackageInstance.class,
              TenantPackageResourceDefinition.class,
              TenantResourceCredit.class,
              TenantResource.class,
              TenantResourceEntitlement.class);
      try (var session = factory.openSession()) {
        var receipt = session.find(TenantPackageInstance.class, "legacy");
        assertThat(receipt.getAcquisition())
            .isEqualTo(TenantPackageInstance.Acquisition.LEGACY_ROLE);
        assertThat(receipt.getAdoptedAt()).isEqualTo(Instant.parse("2026-01-01T01:00:00Z"));
        assertThat(receipt.getActivatedAt()).isEqualTo(receipt.getAdoptedAt());
        assertThat(
                session
                    .createQuery("select count(i) from TenantPackageInstance i", Long.class)
                    .getSingleResult())
            .isEqualTo(1);
        assertThat(
                session
                    .createQuery("select count(c) from TenantResourceCredit c", Long.class)
                    .getSingleResult())
            .isZero();
        assertThat(
                session
                    .createQuery("select count(r) from TenantResource r", Long.class)
                    .getSingleResult())
            .isZero();
      }
    }
  }

  @Test
  void bootstrapStateMigrationPreservesExistingReadinessAndAdoptionHistory() throws Exception {
    try (var database =
        new TenantEntitlementDatabase(
            (connection, migration) -> {
              if (migration.equals("V16__tenant_bootstrap_resource_preparation.sql")) {
                try (var statement = connection.createStatement()) {
                  statement.execute(
                      """
                      insert into simpoint_saas_tenants (id,name,owner_id,tenant_type,resource_mode,authorization_version)
                        values ('ready','Ready','owner','ORGANIZATION','PLATFORM',4),
                               ('legacy','Legacy','owner','ORGANIZATION','PLATFORM',2);
                      insert into simpoint_saas_tenant_resource_setup (tenant_id,state,initialized_at,adopted_at,adopted_by)
                        values ('ready','READY','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z','installation'),
                               ('legacy','ADOPTION_REQUIRED',null,null,null);
                      """);
                }
              }
            })) {
      var factory = database.create(TenantResourceSetup.class);
      try (var session = factory.openSession()) {
        var ready = session.find(TenantResourceSetup.class, "ready");
        assertThat(ready.getState()).isEqualTo(TenantResourceSetup.State.READY);
        assertThat(ready.getInitializedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(ready.getAdoptedAt()).isEqualTo(ready.getInitializedAt());
        assertThat(ready.getAdoptedBy()).isEqualTo("installation");
        var legacy = session.find(TenantResourceSetup.class, "legacy");
        assertThat(legacy.getState()).isEqualTo(TenantResourceSetup.State.ADOPTION_REQUIRED);
        assertThat(legacy.getInitializedAt()).isNull();
        assertThat(legacy.getAdoptedAt()).isNull();
      }
    }
  }

  @Test
  void existingActiveTenantsRequireExplicitAdoptionWithoutCreatingResourcesOrRights()
      throws Exception {
    try (var database =
        new TenantEntitlementDatabase(
            (connection, migration) -> {
              if (migration.equals("V14__tenant_resource_setup.sql")) {
                try (var statement = connection.createStatement()) {
                  statement.execute(
                      """
                      insert into simpoint_saas_tenants (id, name, owner_id, tenant_type, resource_mode, deleted_at)
                      values ('legacy-platform', 'Legacy platform tenant', 'owner', 'ORGANIZATION', 'PLATFORM', null),
                        ('legacy-custom', 'Legacy custom tenant', 'owner', 'ORGANIZATION', 'CUSTOM', null),
                        ('legacy-personal', 'Legacy personal tenant', 'owner', 'PERSONAL', 'PLATFORM', null),
                        ('deleted-tenant', 'Deleted tenant', 'owner', 'ORGANIZATION', 'PLATFORM', '2026-10-01T00:00:00Z');
                      """);
                }
              }
            })) {
      var factory =
          database.create(
              TenantResourceSetup.class, TenantResource.class, TenantResourceEntitlement.class);
      try (var session = factory.openSession()) {
        var rows =
            session
                .createQuery("from TenantResourceSetup", TenantResourceSetup.class)
                .getResultList();
        assertThat(rows)
            .extracting(TenantResourceSetup::getTenantId)
            .containsExactlyInAnyOrder("legacy-platform", "legacy-custom", "legacy-personal");
        assertThat(rows)
            .allSatisfy(
                row -> {
                  assertThat(row.getState()).isEqualTo(TenantResourceSetup.State.ADOPTION_REQUIRED);
                  assertThat(row.getInitializedAt()).isNull();
                  assertThat(row.getAdoptedAt()).isNull();
                });
        assertThat(
                session
                    .createQuery("select count(r) from TenantResource r", Long.class)
                    .getSingleResult())
            .isZero();
        assertThat(
                session
                    .createQuery("select count(e) from TenantResourceEntitlement e", Long.class)
                    .getSingleResult())
            .isZero();
      }
    }
  }

  @Test
  void historicalIdentifierOnlyPackageRemainsReadableWithoutInventingLabelsOrRevocationActors()
      throws Exception {
    try (var database =
        new TenantEntitlementDatabase(
            (connection, migration) -> {
              if (migration.equals("V12__tenant_package_revocation_actor.sql")) {
                try (var statement = connection.createStatement()) {
                  statement.execute(
                      """
                      insert into simpoint_saas_tenant_package_instances
                        (id, tenant_id, package_code, package_name, duration_days, state, assigned_at, assigned_by, revoked_at)
                      values ('historical-instance', 'tenant-a', 'historical', 'Historical package', 30, 'REVOKED',
                        '2026-10-01T00:00:00Z', 'platform-admin', '2026-10-02T00:00:00Z');
                      insert into simpoint_saas_tenant_package_instance_resources (instance_id, resource_id)
                      values ('historical-instance', 'source');
                      """);
                }
              }
            })) {
      var factory =
          database.create(
              TenantPackageInstance.class,
              TenantPackageResourceDefinition.class,
              TenantResourceCredit.class,
              TenantResourceEntitlement.class,
              TenantResource.class);
      try (var session = factory.openSession()) {
        var instance = session.find(TenantPackageInstance.class, "historical-instance");
        assertThat(instance.getResourceIds()).containsExactly("source");
        assertThat(instance.getRevokedBy()).isNull();
        assertThat(instance.getAcquisition()).isEqualTo(TenantPackageInstance.Acquisition.ASSIGNED);
        assertThat(instance.getAdoptedAt()).isNull();
        var page =
            new TenantPackageResourceQuery(session)
                .query(
                    "tenant-a",
                    instance.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 10),
                    Instant.parse("2026-10-23T00:00:00Z"));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().resourceName()).isEqualTo("source");
        assertThat(page.getContent().getFirst().revokedAt())
            .isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
      }
    }
  }

  @Test
  void mirrorBackfillPreservesTenantOverridesAndDoesNotCreateEntitlementsOrReviveSources()
      throws Exception {
    try (var database =
        new TenantEntitlementDatabase(
            (connection, migration) -> {
              if (!migration.equals("V11__tenant_resource_mirror.sql")) {
                return;
              }
              try (var statement = connection.createStatement()) {
                statement.execute(
                    """
                    insert into simpoint_ac_resources
                      (id, code, name, type, scope_types, disabled, grantable, public_access, require_org_tenant,
                       resource_alias, title, label, plugin_id, method, pattern, permission_metadata, danger)
                    values ('source', 'platform.source', 'Platform name', 'PAGE', 'TENANT', false, true, true, false,
                      'source-alias', 'Source title', 'Source label', 'common', 'GET', '/source/**', '{"fields":[]}', true),
                      ('disabled', 'platform.disabled', 'Disabled source', 'PAGE', 'TENANT', true, true, false, false,
                       null, null, null, null, null, null, null, false),
                      ('deleted', 'platform.deleted', 'Deleted source', 'PAGE', 'TENANT', false, true, false, false,
                       null, null, null, null, null, null, null, false);
                    update simpoint_ac_resources set deleted_at = '2026-10-01T00:00:00Z' where id = 'deleted';
                    insert into simpoint_saas_tenant_resources
                      (tenant_id, id, platform_resource_id, code, name, type, enabled, manual_disabled, local_modified,
                       created_at, updated_at)
                    values ('tenant-a', 'source', 'source', 'platform.source', 'My local title', 'PAGE', false, true, true,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z'),
                      ('tenant-b', 'source', 'source', 'platform.source', 'Another tenant title', 'PAGE', true, false, false,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z'),
                      ('tenant-a', 'disabled', 'disabled', 'platform.disabled', 'Disabled', 'PAGE', true, false, false,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z'),
                      ('tenant-a', 'deleted', 'deleted', 'platform.deleted', 'Deleted', 'PAGE', true, false, false,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z'),
                      ('tenant-a', 'missing', 'missing', 'platform.missing', 'Missing', 'PAGE', true, false, false,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z'),
                      ('tenant-a', 'custom', null, 'tenant.custom', 'Custom', 'PAGE', true, false, false,
                       '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z');
                    """);
              }
            })) {
      var factory = database.create(TenantResource.class, TenantResourceEntitlement.class);
      try (var session = factory.openSession()) {
        var row = session.find(TenantResource.class, new TenantResourceKey("tenant-a", "source"));
        assertThat(row.getName()).isEqualTo("My local title");
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.isManualDisabled()).isTrue();
        assertThat(row.isLocalModified()).isTrue();
        assertThat(row.isBaselineEntitled()).isFalse();
        assertThat(row.isSourceAvailable()).isTrue();
        assertThat(row.getAlias()).isEqualTo("source-alias");
        assertThat(row.getScopeTypes()).containsExactly(ResourceScopeType.TENANT);
        assertThat(row.getPermissionMetadata()).isEqualTo("{\"fields\":[]}");
        assertThat(row.isPublicAccess()).isTrue();
        assertThat(row.isDanger()).isTrue();
        assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(row.getLastSyncedAt()).isNull();
        assertThat(
                session
                    .find(TenantResource.class, new TenantResourceKey("tenant-b", "source"))
                    .getName())
            .isEqualTo("Another tenant title");
        for (String id : java.util.List.of("disabled", "deleted", "missing")) {
          assertThat(
                  session
                      .find(TenantResource.class, new TenantResourceKey("tenant-a", id))
                      .isSourceAvailable())
              .isFalse();
        }
        assertThat(
                session
                    .find(TenantResource.class, new TenantResourceKey("tenant-a", "custom"))
                    .isSourceAvailable())
            .isTrue();
        assertThat(
                session
                    .createQuery("select count(e) from TenantResourceEntitlement e", Long.class)
                    .getSingleResult())
            .isZero();
      }
    }
  }
}
