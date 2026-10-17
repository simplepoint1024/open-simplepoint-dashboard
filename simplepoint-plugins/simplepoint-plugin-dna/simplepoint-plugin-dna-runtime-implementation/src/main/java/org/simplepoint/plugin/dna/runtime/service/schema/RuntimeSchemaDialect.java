package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityProvider;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Internal schema compiler and inspector paired with a public capability provider. */
public interface RuntimeSchemaDialect extends DialectCapabilityProvider {

  /** Stable dialect id shared with the capability descriptor. */
  String dialectId();

  /** Supplies trusted Record SQL rendering, including database-specific precision semantics. */
  default SqlDialectFactory recordSqlDialectFactory() {
    return SqlDialectFactoryImpl.INSTANCE;
  }

  /** Rejects values that the target's native JSON representation cannot store without changing them. */
  default void validateRecordJson(final String canonical) {
  }

  /** Requires a tested shared/exclusive transaction-lock implementation; unsupported dialects fail closed. */
  default RecordWriteFenceSql recordWriteFenceSql(final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context, final PhysicalSchemaTarget model) {
    throw new DnaRuntimeException(DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED,
        "This dialect does not support transactional record write fences");
  }

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
