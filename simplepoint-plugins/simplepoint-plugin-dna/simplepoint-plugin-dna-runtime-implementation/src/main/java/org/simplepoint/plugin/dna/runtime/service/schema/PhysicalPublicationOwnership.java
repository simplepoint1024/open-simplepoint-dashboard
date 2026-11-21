package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;

/**
 * Holds the same physical namespace used by model registration for the whole publication attempt.
 */
final class PhysicalPublicationOwnership {
  private PhysicalPublicationOwnership() {}

  static PhysicalOwnershipSession open(
      Connection connection, ManagedRuntimeDataSource handle, PhysicalSchemaTarget target)
      throws SQLException {
    var expected = target.ownership();
    if (expected == null) {
      throw unavailable("Physical model ownership requires verified legacy adoption");
    }
    PhysicalOwnershipSession session;
    try {
      session =
          PhysicalOwnershipSession.open(
              connection, PhysicalModelOwnershipRegistry.namespace(connection, handle));
    } catch (PhysicalOwnershipException failure) {
      throw new DnaRuntimeException(
          failure.code() == PhysicalOwnershipException.Code.SESSION_BUSY
              ? DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT
              : DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
          "Physical publication ownership session is unavailable",
          failure);
    } catch (SQLException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
          "Physical publication ownership session did not open",
          failure);
    }
    try {
      if (!expected.namespaceId().equals(session.namespaceId().toString())) {
        throw conflict();
      }
      requireOwner(
          session, expected, target.physicalModelId(), target.metadataTarget().tableName());
      for (var reference : target.referenceTargets().entrySet()) {
        var own = target.metadataTarget();
        var dependency = reference.getValue();
        if (!own.dataSourceId().equals(dependency.dataSourceId())
            || !own.catalogName().equals(dependency.catalogName())
            || !own.schemaName().equals(dependency.schemaName())) {
          throw conflict();
        }
        requireOwner(session, expected, reference.getKey(), dependency.tableName());
      }
      return session;
    } catch (SQLException | RuntimeException failure) {
      try {
        session.close();
      } catch (SQLException | RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      if (failure instanceof SQLException sqlFailure) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
            "Physical publication ownership could not be verified",
            sqlFailure);
      }
      throw failure;
    }
  }

  private static void requireOwner(
      PhysicalOwnershipSession session,
      PhysicalModelOwnership expected,
      String modelId,
      String table)
      throws SQLException {
    if (session.owner(table, true).filter(expected.owner(modelId)::equals).isEmpty()) {
      throw conflict();
    }
  }

  static void reserveInfrastructure(
      PhysicalOwnershipSession session, RuntimeInfrastructureTable table) {
    try {
      session.claimNew(table.table(), true, table.owner());
    } catch (PhysicalOwnershipException failure) {
      throw new DnaRuntimeException(
          switch (failure.code()) {
            case OWNER_CONFLICT, EXISTING_OBJECT_REQUIRES_ADOPTION, RESERVED_OBJECT ->
                DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT;
            default -> DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE;
          },
          "Physical infrastructure ownership reservation was rejected",
          failure);
    } catch (SQLException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
          "Physical infrastructure ownership reservation did not complete",
          failure);
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT,
        "Physical publication ownership does not match the persisted model binding");
  }

  private static DnaRuntimeException unavailable(String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE, message);
  }
}
