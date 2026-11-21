package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetReview;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipModelReview;

/**
 * Offline structural audit reusing the domain's actual schema dialects and infrastructure registry.
 */
public final class PhysicalOwnershipSchemaInspector {
  private final RuntimeSchemaDialectResolver runtime;
  private final List<JdbcDatabaseDialect> core;

  /**
   * Receives explicitly selected dialect contributions; missing/ambiguous providers fail closed.
   */
  public PhysicalOwnershipSchemaInspector(
      List<RuntimeSchemaDialect> runtime, List<JdbcDatabaseDialect> core) {
    this.runtime = new RuntimeSchemaDialectResolver(runtime);
    this.core = List.copyOf(core);
  }

  /**
   * Verifies active tables against completed publication checksums, unpublished targets as absent,
   * and shared tables against their owning registry. This does not claim, repair or activate
   * tables.
   */
  public List<Table> inspect(
      PhysicalOwnershipModelReview models,
      PhysicalBindingTargetReview targets,
      Map<PhysicalBindingTargetResolver.Source, DataSource> sources) {
    sources = Map.copyOf(sources);
    var fresh = PhysicalBindingTargetResolver.review(models.inventory(), sources);
    if (!fresh.fingerprint().equals(targets.fingerprint())) {
      throw reject("TARGET_CHANGED");
    }
    var physical =
        targets.bindings().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    PhysicalBindingTargetReview.Binding::bindingId, value -> value));
    var modelsById =
        models.inventory().bindings().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Binding
                        ::modelId,
                    value -> value));
    var activeDefinitions =
        models.definitions().stream()
            .filter(
                definition ->
                    Integer.valueOf(definition.number())
                        .equals(modelsById.get(definition.modelId()).model().activeVersion()))
            .collect(
                java.util.stream.Collectors.toMap(
                    PhysicalOwnershipModelReview.Definition::modelId, value -> value));
    var tablesByNamespace =
        targets.bindings().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    binding -> binding.namespace().target(),
                    java.util.stream.Collectors.mapping(
                        PhysicalBindingTargetReview.Binding::table,
                        java.util.stream.Collectors.toSet())));
    var publications =
        models.publications().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    p -> List.of(p.modelId(), Integer.toString(p.targetVersion())),
                    value -> value));
    var infrastructure = new HashSet<PhysicalNamespaceDescriptor>();
    var verified = new ArrayList<Table>();
    for (var binding : models.inventory().bindings()) {
      var resolved = physical.get(binding.bindingId());
      var namespace = resolved.namespace().target();
      var source =
          sources.get(
              new PhysicalBindingTargetResolver.Source(
                  binding.target().dataSourceId(), binding.target().catalogName()));
      try (var connection = source.getConnection()) {
        if (!connection.getAutoCommit()) {
          throw reject("TRANSACTION");
        }
        if (!resolved
            .namespace()
            .equals(PhysicalOwnershipInstaller.inspect(connection, namespace.namespace()))) {
          throw reject("TARGET_CHANGED");
        }
        var selection = select(connection, namespace);
        var database = selection.database();
        var dialect = selection.runtime();
        var context = selection.context();
        var selected = selection.core();
        var target =
            new PhysicalSchemaTarget(
                binding.modelId(),
                new RuntimeMetadataTarget(
                    binding.target().dataSourceId(),
                    namespace.database(),
                    context.currentSchema(),
                    resolved.table()));
        boolean nativeExists = nativeTable(connection, namespace, resolved.table());
        var actual = dialect.inspect(connection, selected, context, target);
        if (nativeExists != actual.exists()) {
          throw reject("METADATA_MISMATCH");
        }
        var definition = activeDefinitions.get(binding.modelId());
        String expected = null;
        if (definition == null) {
          if (actual.exists()) {
            throw reject("UNPUBLISHED_OBJECT");
          }
        } else {
          var publication =
              publications.get(List.of(binding.modelId(), Integer.toString(definition.number())));
          if (!publication.dialect().equals(dialect.dialectId())
              || !actual.exists()
              || !actual.checksum().equals(publication.physicalChecksum())) {
            throw reject("MODEL_DRIFT");
          }
          expected = publication.physicalChecksum();
        }
        verified.add(
            new Table(
                namespace,
                resolved.table(),
                new PhysicalObjectOwner("dna-runtime", binding.tenantId(), binding.modelId()),
                actual.exists(),
                actual.checksum(),
                expected));
        if (infrastructure.add(namespace)) {
          for (var table : RuntimeInfrastructureTable.values()) {
            if (tablesByNamespace.get(namespace).contains(table.table())) {
              throw reject("RESERVED_TABLE");
            }
            var plan =
                dialect.planInitial(
                    connection,
                    selected,
                    context,
                    database,
                    table.target(target),
                    table.description(),
                    table.definition());
            var existing = plan.actualSnapshot();
            if (nativeTable(connection, namespace, table.table()) != existing.exists()) {
              throw reject("METADATA_MISMATCH");
            }
            if (existing.exists()
                && !existing.checksum().equals(plan.targetSnapshot().checksum())) {
              throw reject("INFRASTRUCTURE_DRIFT");
            }
            verified.add(
                new Table(
                    namespace,
                    table.table(),
                    table.owner(),
                    existing.exists(),
                    existing.checksum(),
                    plan.targetSnapshot().checksum()));
          }
        }
        if (!resolved
            .namespace()
            .equals(PhysicalOwnershipInstaller.inspect(connection, namespace.namespace()))) {
          throw reject("TARGET_CHANGED");
        }
      } catch (SQLException failure) {
        throw new IllegalStateException(
            "DNA_OWNERSHIP_SCHEMA_UNAVAILABLE",
            new SQLException(
                "Physical schema review failed", failure.getSQLState(), failure.getErrorCode()));
      }
    }
    // Keep datasource defaults and directory identity bound to the original review throughout.
    if (!targets
        .fingerprint()
        .equals(PhysicalBindingTargetResolver.review(models.inventory(), sources).fingerprint())) {
      throw reject("TARGET_CHANGED");
    }
    return verified.stream()
        .sorted(
            Comparator.comparing((Table t) -> t.namespace().serverIdentity())
                .thenComparing(t -> t.namespace().databaseIdentity())
                .thenComparing(t -> t.namespace().namespaceIdentity())
                .thenComparing(Table::table))
        .toList();
  }

  private static IllegalStateException reject(String code) {
    return new IllegalStateException("DNA_OWNERSHIP_SCHEMA_" + code);
  }

  /**
   * Rechecks exactly the reviewed claims on the caller's locked adoption connection, without
   * writes.
   */
  public void verifyReviewed(
      Connection connection,
      org.simplepoint.data.ownership.PhysicalAdoptionManifest manifest,
      List<Table> evidence)
      throws SQLException {
    if (!connection.getAutoCommit()
        || !manifest
            .target()
            .equals(
                PhysicalNamespaceDescriptor.capture(connection, manifest.target().namespace()))) {
      throw reject("TARGET_CHANGED");
    }
    var reviewed =
        evidence.stream().collect(java.util.stream.Collectors.toMap(Table::table, value -> value));
    if (reviewed.size() != manifest.claims().size()) {
      throw reject("INVALID_EVIDENCE");
    }
    var selection = select(connection, manifest.target());
    for (var claim : manifest.claims()) {
      var expected = reviewed.get(claim.table());
      if (expected == null
          || !expected.namespace().equals(manifest.target())
          || !expected.owner().equals(claim.owner())
          || expected.exists() != claim.exists()
          || !expected.actualChecksum().equals(claim.structureChecksum())) {
        throw reject("INVALID_EVIDENCE");
      }
      var target =
          new PhysicalSchemaTarget(
              claim.owner().resourceId(),
              new RuntimeMetadataTarget(
                  "offline-installation",
                  manifest.target().database(),
                  selection.context().currentSchema(),
                  claim.table()));
      if (nativeTable(connection, manifest.target(), claim.table()) != expected.exists()) {
        throw reject("MODEL_DRIFT");
      }
      var actual =
          selection.runtime().inspect(connection, selection.core(), selection.context(), target);
      if (!actual.checksum().equals(expected.actualChecksum())) {
        throw reject("MODEL_DRIFT");
      }
    }
  }

  private Selection select(Connection connection, PhysicalNamespaceDescriptor namespace)
      throws SQLException {
    var metadata = connection.getMetaData();
    var database =
        new DatabaseIdentity(
            metadata.getDatabaseProductName(),
            metadata.getDatabaseProductVersion(),
            metadata.getDatabaseMajorVersion(),
            metadata.getDatabaseMinorVersion());
    var dialect = runtime.resolve(database);
    if (!dialect.dialectId().equals(namespace.dialect())) {
      throw reject("DIALECT");
    }
    var context =
        new JdbcDatabaseDialect.SupportContext(
            namespace.dialect(),
            metadata.getDriverName(),
            database.productName(),
            database.productVersion(),
            namespace.database(),
            namespace.dialect().equals("postgresql") ? namespace.namespace() : null,
            metadata.getIdentifierQuoteString(),
            Map.of());
    var candidates =
        core.stream()
            .filter(c -> c.code().equals(dialect.dialectId()) && c.supports(context))
            .toList();
    if (candidates.size() != 1) {
      throw reject("DIALECT");
    }
    return new Selection(database, dialect, candidates.getFirst(), context);
  }

  private record Selection(
      DatabaseIdentity database,
      RuntimeSchemaDialect runtime,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context) {}

  private static boolean nativeTable(
      Connection connection, PhysicalNamespaceDescriptor namespace, String table)
      throws SQLException {
    boolean postgres = namespace.dialect().equals("postgresql");
    String sql =
        postgres
            ? "SELECT c.relkind,c.relpersistence,EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i"
                + " WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) FROM pg_catalog.pg_class c JOIN"
                + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND"
                + " c.relname=?"
            : "SELECT TABLE_TYPE,ENGINE,CREATE_OPTIONS FROM information_schema.TABLES"
                + " WHERE BINARY TABLE_SCHEMA=? AND BINARY TABLE_NAME=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(30);
      statement.setString(1, namespace.namespace());
      statement.setString(2, table);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return false;
        }
        boolean supported =
            postgres
                ? "r".equals(rows.getString(1))
                    && "p".equals(rows.getString(2))
                    && !rows.getBoolean(3)
                : "BASE TABLE".equals(rows.getString(1))
                    && "InnoDB".equals(rows.getString(2))
                    && (rows.getString(3) == null || !rows.getString(3).contains("partitioned"));
        if (!supported || rows.next()) {
          throw reject("OBJECT_KIND");
        }
        return true;
      }
    }
  }

  /**
   * Exact physical evidence and intended domain owner; absent infrastructure is not created here.
   */
  public record Table(
      PhysicalNamespaceDescriptor namespace,
      String table,
      PhysicalObjectOwner owner,
      boolean exists,
      String actualChecksum,
      String expectedChecksum) {
    /** Validates bounded native evidence; this record conveys no execution permission. */
    public Table {
      java.util.Objects.requireNonNull(namespace, "namespace");
      java.util.Objects.requireNonNull(owner, "owner");
      if (table == null
          || table.isBlank()
          || table.length() > 255
          || table.indexOf('\0') >= 0
          || actualChecksum == null
          || !actualChecksum.matches("[0-9a-f]{64}")
          || expectedChecksum != null && !expectedChecksum.matches("[0-9a-f]{64}")) {
        throw reject("INVALID_EVIDENCE");
      }
    }
  }
}
