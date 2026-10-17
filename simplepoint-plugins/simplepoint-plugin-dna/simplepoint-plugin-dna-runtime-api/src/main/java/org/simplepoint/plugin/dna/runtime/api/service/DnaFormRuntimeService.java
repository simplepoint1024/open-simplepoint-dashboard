package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorDecoder;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.remoting.RemoteContract;

/** Narrow service-router contract used by APP form publication and runtime. */
@RemoteContract(name = "dna.form-runtime", version = "1", errorDecoder = DnaRuntimeErrorDecoder.class)
public interface DnaFormRuntimeService {

  /** Registers the stable physical model for one APP form. */
  PhysicalModelView registerModel(
      DnaInvocationContext context,
      PhysicalModelRegistrationCommand command
  );

  /** Reads one tenant-owned physical model. */
  PhysicalModelView getModel(DnaInvocationContext context, String physicalModelId);

  /** Resolves a form-owned model after an uncertain registration response. */
  PhysicalModelView getModelByForm(
      DnaInvocationContext context,
      String appId,
      String formId
  );

  /** Appends an immutable physical schema version. */
  PhysicalModelVersionView createModelVersion(
      DnaInvocationContext context,
      String physicalModelId,
      PhysicalModelVersionCommand command
  );

  /** Produces a structured publication plan without exposing native SQL. */
  SchemaPlanView planSchema(
      DnaInvocationContext context,
      String physicalModelId,
      int targetVersion
  );

  /** Publishes one schema version through the durable DNA state machine. */
  SchemaPublicationView publishSchema(
      DnaInvocationContext context,
      String physicalModelId,
      SchemaPublicationCommand command
  );

  /** Reads a schema publication owned by the current tenant. */
  SchemaPublicationView getSchemaPublication(DnaInvocationContext context, String migrationId);

  /** Resolves a publication after a publish response is lost. */
  SchemaPublicationView getSchemaPublicationByTarget(
      DnaInvocationContext context,
      String physicalModelId,
      int targetVersion
  );

  /** Retries one failed schema publication. */
  SchemaPublicationView retrySchemaPublication(
      DnaInvocationContext context,
      String migrationId,
      long expectedLockVersion
  );

  /** Fences one stale schema publication so it can be retried safely. */
  SchemaPublicationView recoverSchemaPublication(
      DnaInvocationContext context,
      String migrationId,
      long expectedLockVersion
  );

  /** Reconciles one page resource to a single model using its durable monotonic generation. */
  PhysicalModelResourceBindingView projectModelResource(
      DnaInvocationContext context,
      PhysicalModelResourceProjectionCommand command
  );

  /** Idempotently binds one published page resource to a physical model. */
  PhysicalModelResourceBindingView bindModelResource(
      DnaInvocationContext context,
      PhysicalModelResourceBindingCommand command
  );

  /** Removes one page resource projection from a physical model. */
  void unbindModelResource(
      DnaInvocationContext context,
      PhysicalModelResourceBindingCommand command
  );

  /** Returns one authorized record page. */
  RecordPageView pageRecords(DnaInvocationContext context, RecordPageQuery query);

  /** Inspects owner-scoped aggregates for publication; the trusted APP caller must authorize publication first. */
  RecordCompletenessView inspectRecordCompleteness(DnaInvocationContext context, RecordCompletenessQuery query);

  /** Publishes owner-authorized write admission after transactional completeness checks. */
  RecordWritePolicyPublicationView publishRecordWritePolicy(
      DnaInvocationContext context, RecordWritePolicyPublicationCommand command);

  /** Cancels the exact owner-authorized intent, or confirms that its target transition already committed. */
  RecordWritePolicyCancellationView cancelRecordWritePolicy(
      DnaInvocationContext context, RecordWritePolicyPublicationCommand command);

  /** Counts authorized records using the structured query contract. */
  long countRecords(DnaInvocationContext context, RecordCountQuery query);

  /** Returns one authorized record. */
  RecordView getRecord(DnaInvocationContext context, RecordLookupQuery query);

  /** Creates one authorized record. */
  RecordCommandResult createRecord(DnaInvocationContext context, RecordCreateCommand command);

  /** Resolves the original create result before APP revalidates changed business policies. */
  RecordCommandResult findRecordCreateResult(DnaInvocationContext context, RecordCreateCommand command);

  /** Finds the exact update receipt before APP revalidates mutable business state, or returns null. */
  RecordCommandResult findRecordUpdateResult(DnaInvocationContext context, RecordUpdateCommand command);

  /** Updates one authorized record using optimistic locking. */
  RecordCommandResult updateRecord(DnaInvocationContext context, RecordUpdateCommand command);

  /** Soft deletes one authorized record using optimistic locking. */
  RecordCommandResult deleteRecord(DnaInvocationContext context, RecordDeleteCommand command);

  /** Executes a bounded atomic mutation group. */
  RecordTransactionResult executeRecordTransaction(
      DnaInvocationContext context,
      RecordTransactionCommand command
  );
}
