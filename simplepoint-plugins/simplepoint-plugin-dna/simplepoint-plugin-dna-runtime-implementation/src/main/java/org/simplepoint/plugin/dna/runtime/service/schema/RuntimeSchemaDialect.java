package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityProvider;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Internal schema compiler and inspector paired with a public capability provider. */
public interface RuntimeSchemaDialect extends DialectCapabilityProvider {

  /** Stable dialect id shared with the capability descriptor. */
  String dialectId();

  /** Prepares a physical connection for portable Record transaction semantics. */
  default void prepareRecordTransaction(final Connection connection) throws SQLException {
  }

  /** Applies session settings required while Calcite executes a Record query. */
  default void prepareRecordQueryConnection(final Connection connection) throws SQLException {
  }

  /** Restores session settings before a Record query connection returns to its pool. */
  default void resetRecordQueryConnection(final Connection connection) throws SQLException {
  }

  /** Compiles a complete initial table plan from logical metadata. */
  InitialSchemaPlan planInitial(
      Connection connection,
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      DatabaseIdentity database,
      PhysicalSchemaTarget target,
      String tableComment,
      PhysicalSchemaDefinition schema
  ) throws SQLException;

  /** Compiles a deterministic migration from the active logical version to its successor. */
  InitialSchemaPlan planEvolution(
      Connection connection,
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      DatabaseIdentity database,
      PhysicalSchemaTarget target,
      String tableComment,
      PhysicalSchemaDefinition currentSchema,
      PhysicalSchemaDefinition targetSchema
  ) throws SQLException;

  /** Reads and canonicalizes the bound table structure. */
  PhysicalTableSnapshot inspect(
      Connection connection,
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      PhysicalSchemaTarget target
  ) throws SQLException;
}
