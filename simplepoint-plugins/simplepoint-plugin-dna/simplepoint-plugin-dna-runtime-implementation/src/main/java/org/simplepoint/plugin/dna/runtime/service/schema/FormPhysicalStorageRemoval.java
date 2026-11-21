package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.springframework.stereotype.Component;

/** Removes dedicated form tables and the form's rows in shared runtime ledgers. */
@Component
public class FormPhysicalStorageRemoval {
  private final ManagedRuntimeDataSourceProvider sources;

  public FormPhysicalStorageRemoval(ManagedRuntimeDataSourceProvider sources) {
    this.sources = sources;
  }

  public record Table(String model, String name) {}

  public record Plan(String dataSource, String catalog, String schema, UUID namespace, List<Table> tables) {}

  public void execute(String tenant, Plan plan) throws SQLException {
    var first = plan.tables().getFirst();
    var target = new PhysicalSchemaTarget(first.model(),
        new RuntimeMetadataTarget(plan.dataSource(), plan.catalog(), plan.schema(), first.name()));
    try (var source = sources.open(target); var connection = source.dataSource().getConnection();
        var directory = PhysicalOwnershipSession.open(connection, PhysicalModelOwnershipRegistry.namespace(connection, source))) {
      var tables = plan.tables().stream().map(table -> new PhysicalOwnershipSession.OwnedTable(
          table.name(), new PhysicalObjectOwner("dna-runtime", tenant, table.model()))).toList();
      var ledgers = ownedLedgers(connection, source, directory);
      // Immutable retirement markers make replay after a lost DDL response safe.
      directory.dropOwnedTables(plan.namespace(), tables);
      var models = plan.tables().stream().map(Table::model).sorted().toList();
      purgeLedgers(connection, source, ledgers, tenant, models);
    }
  }

  private static Set<RuntimeInfrastructureTable> ownedLedgers(Connection connection,
      ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource source,
      PhysicalOwnershipSession directory) throws SQLException {
    var present = EnumSet.noneOf(RuntimeInfrastructureTable.class);
    for (var table : RuntimeInfrastructureTable.values()) {
      var owner = directory.owner(table.table(), true);
      if (exists(connection, source, table.table())) {
        if (owner.isEmpty() || !table.owner().equals(owner.get())) {
          throw new SQLException("Shared form storage ownership has changed");
        }
        present.add(table);
      }
    }
    if (present.contains(RuntimeInfrastructureTable.PUBLICATION_CHUNKS)
        && !present.contains(RuntimeInfrastructureTable.PUBLICATION_STAGES)) {
      throw new SQLException("Shared publication manifests are missing");
    }
    return present;
  }

  private static void purgeLedgers(Connection connection, ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource source,
      Set<RuntimeInfrastructureTable> present, String tenant, List<String> models) throws SQLException {
    String placeholders = String.join(",", Collections.nCopies(models.size(), "?"));
    var stages = RuntimeInfrastructureTable.PUBLICATION_STAGES;
    var chunks = RuntimeInfrastructureTable.PUBLICATION_CHUNKS;
    if (present.contains(chunks)) {
      delete(connection, "DELETE FROM " + qualified(source, chunks)
          + " WHERE tenant_id = ? AND stage_id IN (SELECT id FROM " + qualified(source, stages)
          + " WHERE tenant_id = ? AND model_id IN (" + placeholders + "))", tenant, models, true);
    }
    for (var table : present) {
      if (table == chunks) continue;
      delete(connection, "DELETE FROM " + qualified(source, table)
          + " WHERE tenant_id = ? AND model_id IN (" + placeholders + ")", tenant, models, false);
    }
  }

  private static void delete(Connection connection, String sql, String tenant, List<String> models,
      boolean nested) throws SQLException {
    try (PreparedStatement step = connection.prepareStatement(sql)) {
      step.setQueryTimeout(30);
      int index = 1;
      step.setString(index++, tenant);
      if (nested) step.setString(index++, tenant);
      for (String model : models) step.setString(index++, model);
      step.executeUpdate();
    }
  }

  private static boolean exists(Connection connection, ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource source,
      String name) throws SQLException {
    try (var results = connection.getMetaData().getTables(
        source.metadataCatalog(), source.metadataSchema(), name, new String[] {"TABLE"})) {
      while (results.next()) {
        if (name.equals(results.getString("TABLE_NAME"))) return true;
      }
      return false;
    }
  }

  private static String qualified(ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource source,
      RuntimeInfrastructureTable table) {
    boolean pg = "postgresql".equals(source.runtimeDialect().dialectId());
    String delimiter = pg ? "\"" : "`";
    String namespace = pg ? source.metadataSchema() : source.metadataCatalog();
    return delimiter + namespace.replace(delimiter, delimiter + delimiter) + delimiter
        + "." + delimiter + table.table() + delimiter;
  }
}
