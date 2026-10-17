package org.simplepoint.plugin.dna.runtime.service.remote;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
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
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordInspectionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordWritePolicyService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/** Trusted service-router adapter that restores one bounded APP invocation context. */
@Service
@RemoteProvider
public class DnaFormRuntimeServiceImpl implements DnaFormRuntimeService {

  private final PhysicalModelService modelService;
  private final SchemaPlanService planService;
  private final SchemaPublicationService publicationService;
  private final PhysicalModelAccessService accessService;
  private final RecordQueryService queryService;
  private final RecordCommandService commandService;
  private final RecordTransactionService transactionService;
  private final RecordInspectionService inspectionService;
  private final RecordWritePolicyService writePolicyService;
  private final DnaInvocationExecutor invocation;

  /** Creates the adapter over the existing DNA domain services. */
  public DnaFormRuntimeServiceImpl(
      final PhysicalModelService modelService,
      final SchemaPlanService planService,
      final SchemaPublicationService publicationService,
      final PhysicalModelAccessService accessService,
      final RecordQueryService queryService,
      final RecordCommandService commandService,
      final RecordTransactionService transactionService,
      final RecordInspectionService inspectionService,
      final RecordWritePolicyService writePolicyService,
      final DnaFormRuntimeCallerGuard callerGuard
  ) {
    this.modelService = modelService;
    this.planService = planService;
    this.publicationService = publicationService;
    this.accessService = accessService;
    this.queryService = queryService;
    this.commandService = commandService;
    this.transactionService = transactionService;
    this.inspectionService = inspectionService;
    this.writePolicyService = writePolicyService;
    this.invocation = new DnaInvocationExecutor(callerGuard);
  }

  @Override
  public PhysicalModelView registerModel(
      final DnaInvocationContext context,
      final PhysicalModelRegistrationCommand command
  ) {
    return invoke(context, false, () -> modelService.register(command));
  }

  @Override
  public PhysicalModelView getModel(
      final DnaInvocationContext context,
      final String physicalModelId
  ) {
    return invoke(context, false, () -> modelService.get(physicalModelId));
  }

  @Override
  public PhysicalModelView getModelByForm(
      final DnaInvocationContext context,
      final String appId,
      final String formId
  ) {
    return invoke(context, false, () -> modelService.getByForm(appId, formId));
  }

  @Override
  public PhysicalModelVersionView createModelVersion(
      final DnaInvocationContext context,
      final String physicalModelId,
      final PhysicalModelVersionCommand command
  ) {
    return invoke(context, false, () -> modelService.createVersion(physicalModelId, command));
  }

  @Override
  public SchemaPlanView planSchema(
      final DnaInvocationContext context,
      final String physicalModelId,
      final int targetVersion
  ) {
    return invoke(context, false, () -> planService.plan(physicalModelId, targetVersion));
  }

  @Override
  public SchemaPublicationView publishSchema(
      final DnaInvocationContext context,
      final String physicalModelId,
      final SchemaPublicationCommand command
  ) {
    return invoke(context, false, () -> publicationService.publish(physicalModelId, command));
  }

  @Override
  public SchemaPublicationView getSchemaPublication(
      final DnaInvocationContext context,
      final String migrationId
  ) {
    return invoke(context, false, () -> publicationService.get(migrationId));
  }

  @Override
  public SchemaPublicationView getSchemaPublicationByTarget(
      final DnaInvocationContext context,
      final String physicalModelId,
      final int targetVersion
  ) {
    return invoke(
        context,
        false,
        () -> publicationService.getByTargetVersion(physicalModelId, targetVersion)
    );
  }

  @Override
  public SchemaPublicationView retrySchemaPublication(
      final DnaInvocationContext context,
      final String migrationId,
      final long expectedLockVersion
  ) {
    return invoke(
        context,
        false,
        () -> publicationService.retry(migrationId, expectedLockVersion)
    );
  }

  @Override
  public SchemaPublicationView recoverSchemaPublication(
      final DnaInvocationContext context,
      final String migrationId,
      final long expectedLockVersion
  ) {
    return invoke(
        context,
        false,
        () -> publicationService.recover(migrationId, expectedLockVersion)
    );
  }

  @Override
  public PhysicalModelResourceBindingView projectModelResource(
      final DnaInvocationContext context,
      final PhysicalModelResourceProjectionCommand command
  ) {
    return invoke(context, false, () -> accessService.project(command));
  }

  @Override
  public PhysicalModelResourceBindingView bindModelResource(
      final DnaInvocationContext context,
      final PhysicalModelResourceBindingCommand command
  ) {
    return invoke(context, false, () -> accessService.bind(command));
  }

  @Override
  public void unbindModelResource(
      final DnaInvocationContext context,
      final PhysicalModelResourceBindingCommand command
  ) {
    invoke(context, false, () -> {
      accessService.unbind(command);
      return null;
    });
  }

  @Override
  public RecordPageView pageRecords(
      final DnaInvocationContext context,
      final RecordPageQuery query
  ) {
    return invoke(context, true, () -> queryService.page(query));
  }

  @Override
  public long countRecords(
      final DnaInvocationContext context,
      final RecordCountQuery query
  ) {
    return invoke(context, true, () -> queryService.count(query));
  }

  @Override
  public RecordCompletenessView inspectRecordCompleteness(
      final DnaInvocationContext context,
      final RecordCompletenessQuery query
  ) {
    return invoke(context, false, () -> inspectionService.inspectCompleteness(query));
  }

  @Override
  public RecordWritePolicyPublicationView publishRecordWritePolicy(final DnaInvocationContext context,
      final RecordWritePolicyPublicationCommand command) {
    return invoke(context, false, () -> writePolicyService.publish(command));
  }

  @Override
  public RecordWritePolicyCancellationView cancelRecordWritePolicy(final DnaInvocationContext context,
      final RecordWritePolicyPublicationCommand command) {
    return invoke(context, false, () -> writePolicyService.cancel(command));
  }

  @Override
  public RecordView getRecord(
      final DnaInvocationContext context,
      final RecordLookupQuery query
  ) {
    return invoke(context, true, () -> queryService.get(query));
  }

  @Override
  public RecordCommandResult createRecord(
      final DnaInvocationContext context,
      final RecordCreateCommand command
  ) {
    return invoke(context, true, () -> commandService.create(command));
  }

  @Override
  public RecordCommandResult findRecordUpdateResult(
      final DnaInvocationContext context,
      final RecordUpdateCommand command
  ) {
    return invoke(context, true, () -> commandService.findUpdateResult(command));
  }

  @Override
  public RecordCommandResult findRecordCreateResult(
      final DnaInvocationContext context,
      final RecordCreateCommand command
  ) {
    return invoke(context, true, () -> commandService.findCreateResult(command));
  }

  @Override
  public RecordCommandResult updateRecord(
      final DnaInvocationContext context,
      final RecordUpdateCommand command
  ) {
    return invoke(context, true, () -> commandService.update(command));
  }

  @Override
  public RecordCommandResult deleteRecord(
      final DnaInvocationContext context,
      final RecordDeleteCommand command
  ) {
    return invoke(context, true, () -> commandService.delete(command));
  }

  @Override
  public RecordTransactionResult executeRecordTransaction(
      final DnaInvocationContext context,
      final RecordTransactionCommand command
  ) {
    return invoke(context, true, () -> transactionService.execute(command));
  }

  private <T> T invoke(final DnaInvocationContext context, final boolean recordAccess, final Supplier<T> callback) {
    return invocation.invoke(context, recordAccess, callback);
  }
}
