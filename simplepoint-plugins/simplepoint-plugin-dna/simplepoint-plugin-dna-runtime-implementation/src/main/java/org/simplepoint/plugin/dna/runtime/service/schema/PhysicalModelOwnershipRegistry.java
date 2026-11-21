package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.SQLException;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.springframework.stereotype.Component;

/** Reserves physical ownership before a control-store binding can become visible. */
@Component
public final class PhysicalModelOwnershipRegistry {
  private final ManagedRuntimeDataSourceProvider dataSources;

  /** Uses the runtime's managed datasource and verified dialect resolution. */
  public PhysicalModelOwnershipRegistry(ManagedRuntimeDataSourceProvider dataSources) {
    this.dataSources = dataSources;
  }

  /**
   * Returns the durable namespace identity from the target database. The reservation deliberately
   * survives rollback of the separate control-store transaction; callers must never compensate it
   * with an automatic release or claim an existing unowned table.
   */
  public String reserve(
      String tenant, String modelId, PhysicalBindingTarget placement, String table) {
    var target =
        new PhysicalSchemaTarget(
            modelId,
            new RuntimeMetadataTarget(
                placement.dataSourceId(), placement.catalogName(), placement.schemaName(), table));
    try (var handle = dataSources.open(target);
        var connection = handle.dataSource().getConnection()) {
      String namespace = namespace(connection, handle);
      try (var session = PhysicalOwnershipSession.open(connection, namespace)) {
        session.claimNew(table, true, new PhysicalObjectOwner("dna-runtime", tenant, modelId));
        return session.namespaceId().toString();
      }
    } catch (PhysicalOwnershipException failure) {
      DnaRuntimeErrorCode code =
          switch (failure.code()) {
            case OWNER_CONFLICT,
                    EXISTING_OBJECT_REQUIRES_ADOPTION,
                    RESERVED_OBJECT,
                    INVALID_OBJECT_NAME ->
                DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT;
            case SESSION_BUSY -> DnaRuntimeErrorCode.PHYSICAL_MODEL_REGISTRATION_CONFLICT;
            default -> DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE;
          };
      throw new DnaRuntimeException(
          code, "Physical model ownership reservation was rejected", failure);
    } catch (SQLException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
          "Physical model ownership reservation did not complete",
          failure);
    }
  }

  static String namespace(
      java.sql.Connection connection,
      ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource handle)
      throws SQLException {
    return switch (handle.runtimeDialect().dialectId()) {
      case "postgresql" -> {
        if (handle.metadataCatalog() != null
            && !handle.metadataCatalog().equals(connection.getCatalog())) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
              "The executing connection does not match the requested database");
        }
        yield handle.metadataSchema();
      }
      case "mysql" -> handle.metadataCatalog();
      default ->
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
              "Physical ownership is not supported by the selected runtime dialect");
    };
  }
}
