package org.simplepoint.plugin.dna.runtime.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePublicationProperties;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PersistedStep;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PublicationClaim;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PublicationWork;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.PlannedSchemaStep;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Service;

/** Orchestrates recoverable target DDL without holding a control-plane transaction open. */
@Service
public class SchemaPublicationServiceImpl implements SchemaPublicationService {

  private final RuntimeRequestContext requestContext;
  private final SchemaPublicationStateService stateService;
  private final JdbcPhysicalSchemaBackend backend;
  private final RuntimeMetadataCacheInvalidator cacheInvalidator;
  private final long staleAfterMs;

  /** Creates the publication orchestrator with a mandatory cache invalidation bridge. */
  public SchemaPublicationServiceImpl(
      final RuntimeRequestContext requestContext,
      final SchemaPublicationStateService stateService,
      final JdbcPhysicalSchemaBackend backend,
      final RuntimeMetadataCacheInvalidator cacheInvalidator,
      final DnaRuntimePublicationProperties publicationProperties
  ) {
    this.requestContext = requestContext;
    this.stateService = stateService;
    this.backend = backend;
    this.cacheInvalidator = cacheInvalidator;
    this.staleAfterMs = publicationProperties.getStaleAfterMs();
  }

  @Override
  public SchemaPublicationView publish(
      final String physicalModelId,
      final SchemaPublicationCommand command
  ) {
    if (command == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "Schema publication command is required"
      );
    }
    RuntimeActor actor = requestContext.requireActor();
    PublicationClaim claim = stateService.claim(actor, physicalModelId, command);
    return claim.execute()
        ? execute(actor, claim.work())
        : stateService.get(actor.tenantId(), claim.work().migrationId());
  }

  @Override
  public SchemaPublicationView get(final String migrationId) {
    RuntimeActor actor = requestContext.requireActor();
    return stateService.get(actor.tenantId(), requireId(migrationId));
  }

  @Override
  public SchemaPublicationView retry(
      final String migrationId,
      final long expectedLockVersion
  ) {
    if (expectedLockVersion < 0) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "expectedLockVersion cannot be negative"
      );
    }
    RuntimeActor actor = requestContext.requireActor();
    PublicationClaim claim = stateService.claimRetry(
        actor,
        requireId(migrationId),
        expectedLockVersion
    );
    return execute(actor, claim.work());
  }

  @Override
  public SchemaPublicationView recover(
      final String migrationId,
      final long expectedLockVersion
  ) {
    if (expectedLockVersion < 0) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "expectedLockVersion cannot be negative"
      );
    }
    RuntimeActor actor = requestContext.requireActor();
    return stateService.recoverStale(
        actor,
        requireId(migrationId),
        expectedLockVersion,
        Instant.now().minusMillis(staleAfterMs)
    );
  }

  private SchemaPublicationView execute(
      final RuntimeActor actor,
      final PublicationWork work
  ) {
    try {
      InitialSchemaPlan plan = backend.plan(
          work.target(),
          work.displayName(),
          work.currentSchema(),
          work.schema()
      );
      if (plan.risk() == org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk.DESTRUCTIVE) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL,
            "Destructive schema changes require a separate controlled cleanup operation"
        );
      }
      stateService.ready(actor, work.migrationId(), work.executionToken(), plan);
      stateService.start(actor, work.migrationId(), work.executionToken());
      executeSteps(actor, work, plan);
      stateService.verifying(actor, work.migrationId(), work.executionToken());
      PhysicalTableSnapshot verified = backend.inspect(work.target(), plan.dialectId());
      if (!verified.checksum().equals(plan.targetSnapshot().checksum())) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_VERIFICATION_FAILED,
            "Published table structure does not match the target snapshot"
        );
      }
      invalidate(work);
      return stateService.activate(
          actor,
          work.migrationId(),
          work.executionToken(),
          verified.checksum()
      );
    } catch (DnaRuntimeException exception) {
      stateService.fail(
          actor,
          work.migrationId(),
          work.executionToken(),
          exception.errorCode()
      );
      throw exception;
    } catch (RuntimeException exception) {
      stateService.fail(
          actor,
          work.migrationId(),
          work.executionToken(),
          DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED
      );
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED,
          "Schema publication failed",
          exception
      );
    }
  }

  private void executeSteps(
      final RuntimeActor actor,
      final PublicationWork work,
      final InitialSchemaPlan plan
  ) {
    Map<Integer, PersistedStep> persistedBySequence = stateService.steps(
        actor.tenantId(),
        work.migrationId()
    ).stream().collect(Collectors.toUnmodifiableMap(
        PersistedStep::sequence,
        Function.identity()
    ));
    if (persistedBySequence.size() != plan.steps().size()) {
      throw drift("Persisted migration step count does not match the validated plan");
    }
    for (PlannedSchemaStep planned : plan.steps()) {
      PersistedStep persisted = persistedBySequence.get(planned.sequence());
      validateStep(planned, persisted);
      PhysicalTableSnapshot actual = backend.inspect(work.target(), plan.dialectId());
      boolean satisfied = backend.isSatisfied(planned, plan.targetSnapshot(), actual);
      boolean completed = persisted.status() == SchemaMigrationStepStatus.SUCCEEDED
          || persisted.status() == SchemaMigrationStepStatus.SKIPPED;
      if (completed) {
        if (!satisfied) {
          throw drift("A completed migration step is no longer reflected in the database");
        }
        continue;
      }
      String stepId = stateService.beginStep(
          actor,
          work.migrationId(),
          work.executionToken(),
          planned.sequence()
      );
      if (satisfied) {
        stateService.finishStep(
            actor,
            work.migrationId(),
            work.executionToken(),
            stepId,
            SchemaMigrationStepStatus.SKIPPED
        );
      } else {
        backend.execute(work.target(), plan.dialectId(), planned);
        stateService.finishStep(
            actor,
            work.migrationId(),
            work.executionToken(),
            stepId,
            SchemaMigrationStepStatus.SUCCEEDED
        );
      }
    }
  }

  private void invalidate(final PublicationWork work) {
    try {
      cacheInvalidator.invalidate(work.target().metadataTarget());
    } catch (RuntimeException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.METADATA_INVALIDATION_FAILED,
          "Published table metadata caches could not be invalidated",
          exception
      );
    }
  }

  private static void validateStep(
      final PlannedSchemaStep planned,
      final PersistedStep persisted
  ) {
    if (persisted == null
        || !planned.operation().name().equals(persisted.operation())
        || !planned.statementChecksum().equals(persisted.statementChecksum())) {
      throw drift("Persisted migration step does not match the validated plan");
    }
  }

  private static String requireId(final String value) {
    if (value == null || value.isBlank()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "migrationId is required"
      );
    }
    return value.trim();
  }

  private static DnaRuntimeException drift(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED, message);
  }
}
