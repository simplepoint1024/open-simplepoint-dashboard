package org.simplepoint.plugin.app.form.service;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Component;

/** Idempotently prepares one APP form schema through the narrow DNA remote contract. */
@Component
public class FormDnaSchemaPublicationCoordinator {

  private final DnaFormRuntimeService dna;

  /** Creates the coordinator over the service-router proxy or a local provider. */
  public FormDnaSchemaPublicationCoordinator(final DnaFormRuntimeService dna) {
    this.dna = dna;
  }

  /** Registers or evolves a physical model and advances a safe schema publication. */
  public PreparedDnaSchema prepare(
      final DnaInvocationContext context, final DnaSchemaPreparationRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");
    PhysicalModelView model = resolveModel(context, request);
    requireOwner(model, request);
    model = ensureSchemaVersion(context, model, request);
    int targetVersion = model.latestSchema().version();
    if (model.status() == PhysicalModelStatus.ACTIVE
        && Objects.equals(model.activeVersion(), targetVersion)) {
      return result(model, targetVersion, null, SchemaMigrationStatus.ACTIVE);
    }

    SchemaPlanView plan = dna.planSchema(context, model.id(), targetVersion);
    if (plan.risk() != SchemaMigrationRisk.SAFE) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED,
          "Form schema publication requires an explicit migration review");
    }
    SchemaPublicationView publication = publish(context, model, targetVersion);
    if (!publication.status().terminal() && request.recoverStalePublication()) {
      publication = recoverAndRetry(context, model, publication);
    }
    if (publication.status() == SchemaMigrationStatus.FAILED) {
      PhysicalModelView failedModel = dna.getModel(context, model.id());
      publication = retry(context, failedModel, publication);
    }
    return result(model, targetVersion, publication.migrationId(), publication.status());
  }

  private PhysicalModelView resolveModel(
      final DnaInvocationContext context, final DnaSchemaPreparationRequest request) {
    if (request.physicalModelId() != null) {
      return dna.getModel(context, request.physicalModelId());
    }
    try {
      return dna.registerModel(
          context,
          new PhysicalModelRegistrationCommand(
              request.applicationId(),
              request.formId(),
              request.displayName(),
              request.placementKey(),
              request.schema()));
    } catch (RuntimeException registrationFailure) {
      try {
        return dna.getModelByForm(context, request.applicationId(), request.formId());
      } catch (RuntimeException lookupFailure) {
        registrationFailure.addSuppressed(lookupFailure);
        throw registrationFailure;
      }
    }
  }

  private PhysicalModelView ensureSchemaVersion(
      final DnaInvocationContext context,
      final PhysicalModelView model,
      final DnaSchemaPreparationRequest request) {
    if (request.storageChecksum().equals(model.latestSchema().checksum())) {
      return model;
    }
    PhysicalModelView updated;
    try {
      dna.createModelVersion(
          context,
          model.id(),
          new PhysicalModelVersionCommand(model.lockVersion(), request.schema()));
      updated = dna.getModel(context, model.id());
    } catch (RuntimeException versionFailure) {
      try {
        updated = dna.getModel(context, model.id());
      } catch (RuntimeException lookupFailure) {
        versionFailure.addSuppressed(lookupFailure);
        throw versionFailure;
      }
      if (!request.storageChecksum().equals(updated.latestSchema().checksum())) {
        throw versionFailure;
      }
    }
    if (!request.storageChecksum().equals(updated.latestSchema().checksum())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "DNA returned a schema version with an unexpected checksum");
    }
    return updated;
  }

  private SchemaPublicationView publish(
      final DnaInvocationContext context, final PhysicalModelView model, final int targetVersion) {
    try {
      return dna.publishSchema(
          context, model.id(), new SchemaPublicationCommand(targetVersion, model.lockVersion()));
    } catch (RuntimeException publicationFailure) {
      return recoverPublication(context, model.id(), targetVersion, publicationFailure);
    }
  }

  private SchemaPublicationView retry(
      final DnaInvocationContext context,
      final PhysicalModelView model,
      final SchemaPublicationView failed) {
    try {
      return dna.retrySchemaPublication(context, failed.migrationId(), model.lockVersion());
    } catch (RuntimeException retryFailure) {
      SchemaPublicationView recovered =
          recoverPublication(context, model.id(), failed.targetVersion(), retryFailure);
      if (recovered.status() == SchemaMigrationStatus.FAILED) {
        throw retryFailure;
      }
      return recovered;
    }
  }

  private SchemaPublicationView recoverAndRetry(
      final DnaInvocationContext context,
      final PhysicalModelView model,
      final SchemaPublicationView publication) {
    PhysicalModelView current = dna.getModel(context, model.id());
    SchemaPublicationView recovered =
        dna.recoverSchemaPublication(context, publication.migrationId(), current.lockVersion());
    if (recovered.status() != SchemaMigrationStatus.FAILED) {
      return recovered;
    }
    PhysicalModelView failed = dna.getModel(context, model.id());
    return retry(context, failed, recovered);
  }

  private SchemaPublicationView recoverPublication(
      final DnaInvocationContext context,
      final String modelId,
      final int targetVersion,
      final RuntimeException originalFailure) {
    try {
      return dna.getSchemaPublicationByTarget(context, modelId, targetVersion);
    } catch (RuntimeException lookupFailure) {
      originalFailure.addSuppressed(lookupFailure);
      throw originalFailure;
    }
  }

  private static void requireOwner(
      final PhysicalModelView model, final DnaSchemaPreparationRequest request) {
    if (!request.applicationId().equals(model.appId())
        || !request.formId().equals(model.formId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Physical model ownership does not match the form publication");
    }
  }

  private static PreparedDnaSchema result(
      final PhysicalModelView model,
      final int targetVersion,
      final String migrationId,
      final SchemaMigrationStatus status) {
    return new PreparedDnaSchema(model.id(), targetVersion, migrationId, status);
  }

  /** Server-owned inputs restored from an immutable APP form version. */
  public record DnaSchemaPreparationRequest(
      String applicationId,
      String formId,
      String displayName,
      String placementKey,
      String physicalModelId,
      PhysicalSchemaDefinition schema,
      String storageChecksum,
      boolean recoverStalePublication) {

    /** Validates stable ownership and schema identity. */
    public DnaSchemaPreparationRequest {
      applicationId = required(applicationId, 64, "applicationId");
      formId = required(formId, 64, "formId");
      displayName = required(displayName, 128, "displayName");
      placementKey =
          placementKey == null || placementKey.isBlank()
              ? "default"
              : required(placementKey, 64, "placementKey");
      physicalModelId = optional(physicalModelId, 64, "physicalModelId");
      Objects.requireNonNull(schema, "schema");
      if (storageChecksum == null || !storageChecksum.matches("[a-fA-F0-9]{64}")) {
        throw new IllegalArgumentException("storageChecksum must be a SHA-256 value");
      }
      storageChecksum = storageChecksum.toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** DNA schema state persisted by the APP publication state machine. */
  public record PreparedDnaSchema(
      String physicalModelId,
      int physicalModelVersion,
      String migrationId,
      SchemaMigrationStatus status,
      String detailBindingsJson) {
    /** Preserves the immutable runtime identity and optional child bindings. */
    public PreparedDnaSchema(
        String physicalModelId,
        int physicalModelVersion,
        String migrationId,
        SchemaMigrationStatus status) {
      this(physicalModelId, physicalModelVersion, migrationId, status, null);
    }

    /** Returns whether DNA has fully activated the target schema. */
    public boolean active() {
      return status == SchemaMigrationStatus.ACTIVE;
    }
  }

  private static String required(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static String optional(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " is too long");
    }
    return value.trim();
  }
}
