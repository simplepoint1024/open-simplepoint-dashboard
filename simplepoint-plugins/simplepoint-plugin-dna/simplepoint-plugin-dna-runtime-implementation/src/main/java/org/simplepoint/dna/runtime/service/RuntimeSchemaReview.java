package org.simplepoint.dna.runtime.service;

import java.util.Objects;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Explicit review of a frozen, non-destructive schema plan through the existing migration engine.
 */
@Service
public class RuntimeSchemaReview {
  private final RuntimeGovernanceCatalog catalog;
  private final SchemaPublicationService publications;

  /**
   * Keeps schema ownership, tenancy, physical prechecks and migration execution in their existing
   * services.
   */
  public RuntimeSchemaReview(
      final RuntimeGovernanceCatalog catalog, final SchemaPublicationService publications) {
    this.catalog = catalog;
    this.publications = publications;
  }

  /** Returns scoped metadata and a fresh server-derived execution capability. */
  public Review inspect(final String modelId) {
    var model = catalog.model(modelId).model();
    var plan = catalog.plan(modelId, model.latestVersion());
    return new Review(
        plan,
        model.lockVersion(),
        permitted()
            && eligible(model.status())
            && plan.risk() != SchemaMigrationRisk.DESTRUCTIVE
            && !Objects.equals(model.activeVersion(), model.latestVersion()));
  }

  /** Never accepts SQL, a replacement definition or a client-declared risk classification. */
  public SchemaPublicationView publish(final String modelId, final Command command) {
    if (!permitted()) {
      throw new AccessDeniedException("Schema publication permission is required");
    }
    if (command == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT, "A reviewed schema intent is required");
    }
    var model = catalog.model(modelId).model();
    if (model.lockVersion() != command.expectedVersion()
        || model.latestVersion() != command.targetVersion()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT,
          "The reviewed model version changed");
    }
    if (!eligible(model.status())
        || Objects.equals(model.activeVersion(), command.targetVersion())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT,
          "The model requires inspection or migration recovery before a new publication");
    }
    var current = catalog.plan(modelId, command.targetVersion());
    if (!current.actualChecksum().equals(command.actualChecksum())
        || !current.targetChecksum().equals(command.targetChecksum())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
          "The reviewed physical or logical schema changed");
    }
    if (current.risk() == SchemaMigrationRisk.DESTRUCTIVE) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL,
          "Destructive changes require the separate controlled cleanup operation");
    }
    // The authoritative engine reacquires its model lock and repeats physical prechecks before DDL.
    return publications.publish(
        modelId, new SchemaPublicationCommand(command.targetVersion(), command.expectedVersion()));
  }

  private static boolean permitted() {
    var context = AuthorizationContextHolder.getContext();
    return context != null
        && (Boolean.TRUE.equals(context.getIsAdministrator())
            || context.getResources() != null
                && context.getResources().contains("dna.runtime.models.publish"));
  }

  private static boolean eligible(final String status) {
    return "DRAFT".equals(status) || "ACTIVE".equals(status);
  }

  /** A review can be displayed without granting permission to execute it. */
  public record Review(SchemaPlanView plan, long expectedVersion, boolean canPublish) {}

  /**
   * Freezes the reviewed model revision and both schema checksums, not a mutable latest-version
   * pointer.
   */
  public record Command(
      int targetVersion, long expectedVersion, String actualChecksum, String targetChecksum) {
    /** Rejects malformed public requests before any metadata or target operation. */
    public Command {
      if (targetVersion < 1
          || expectedVersion < 0
          || actualChecksum == null
          || targetChecksum == null
          || !actualChecksum.matches("[a-f0-9]{64}")
          || !targetChecksum.matches("[a-f0-9]{64}")) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.INVALID_ARGUMENT, "Invalid reviewed schema identity");
      }
    }
  }
}
