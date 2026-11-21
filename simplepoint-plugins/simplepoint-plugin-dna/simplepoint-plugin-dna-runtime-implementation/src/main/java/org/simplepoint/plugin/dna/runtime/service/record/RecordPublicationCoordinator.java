package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionPreflightQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/**
 * Composes control locks, fresh trusted metadata and one committed target batch; no background
 * authorization snapshot.
 */
@Component
public class RecordPublicationCoordinator {
  private final RecordPublicationControlFence control;
  private final RecordPublicationMetadata metadata;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordPublicationBatchExecutor batches;
  private final RecordRequestFingerprint fingerprints;
  private final RecordPublicationStore journal = new RecordPublicationStore();
  private final RecordCalculationStore calculations = new RecordCalculationStore();

  /**
   * The authorized service supplies the current trusted actor; recovery reuses the durable original
   * command and clock.
   */
  public RecordPublicationCoordinator(
      final RecordPublicationControlFence control,
      final RecordPublicationMetadata metadata,
      final RuntimeCalciteRecordBackend backend,
      final RecordPublicationBatchExecutor batches,
      final RecordRequestFingerprint fingerprints) {
    this.control = control;
    this.metadata = metadata;
    this.backend = backend;
    this.batches = batches;
    this.fingerprints = fingerprints;
  }

  long inspectReferenceCollection(
      RuntimeActor actor, RecordReferenceCollectionPreflightQuery query) {
    var deadline = new RecordExecutionDeadline(30_000);
    var key = new RecordWriteFenceKey(actor.tenantId(), query.physicalModelId());
    var ids = new LinkedHashSet<String>();
    ids.add(key.modelId());
    ids.add(query.destination().edgeModelId());
    ids.add(query.destination().targetModelId());
    return control.execute(
        actor.tenantId(),
        ids,
        deadline,
        locked -> {
          var binding = metadata.resolveConversion(actor, query);
          var children =
              binding.source().physicalModelId().equals(binding.target().physicalModelId())
                  ? List.of(binding.edge())
                  : List.of(binding.edge(), binding.target());
          return backend.publication(
              binding.source(),
              children,
              key,
              deadline.remainingMillis(),
              session -> {
                if (session.admission().revision() != query.expectedPolicyVersion()
                    || journal.readActive(key, session.read(journal.active(key))) != null) {
                  throw conflict();
                }
                var installed =
                    calculations.read(
                        key,
                        query.expectedPolicyVersion(),
                        session.read(calculations.lookup(key, query.expectedPolicyVersion())));
                if (installed == null
                    || !installed.policy().appId().equals(query.appId())
                    || !installed.policy().formId().equals(query.formId())) {
                  throw conflict();
                }
                var policy = installed.policy();
                String destination = query.destination().relationId();
                if (policy.retiredRelations().contains(destination)
                    || policy.definition().referencesOrEmpty().stream()
                        .anyMatch(rule -> rule.relationId().equals(destination))
                    || policy.definition().collectionsOrEmpty().stream()
                        .anyMatch(
                            rule ->
                                rule.relationId().equals(destination)
                                    || rule.edgeModelId()
                                        .equals(query.destination().edgeModelId()))) {
                  throw conflict();
                }
                var source =
                    policy.definition().referencesOrEmpty().stream()
                        .filter(
                            rule ->
                                !rule.retired()
                                    && rule.relationId().equals(query.sourceRelationId()))
                        .findFirst()
                        .orElseThrow(RecordPublicationCoordinator::conflict);
                return new RecordReferenceCollectionConversion(key, source, binding)
                    .inspect(session);
              });
        });
  }

  RecordPublicationStore.Entry advance(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final boolean cancel) {
    return advance(
        actor,
        command,
        cancel,
        false,
        new RecordExecutionDeadline(Math.min(command.timeoutMs(), 30_000)));
  }

  private RecordPublicationStore.Entry advance(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final boolean cancel,
      final boolean validateUnstartedCancellation,
      final RecordExecutionDeadline deadline) {
    var attempted = new AtomicReference<RecordPublicationStore.Entry>();
    final String operationAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    try {
      if (command.referenceConversion() != null
          || command.collectionRebind() != null
          || !cancel && !command.retiringCollections().isEmpty()) {
        var replay = retiredPublicationResult(actor, command, deadline);
        if (replay != null) {
          return replay;
        }
      }
      RecordPublicationStore.Entry outcome =
          execute(
              actor,
              command,
              !cancel || validateUnstartedCancellation,
              true,
              deadline,
              (snapshot, session) -> {
                var key = new RecordWriteFenceKey(actor.tenantId(), command.physicalModelId());
                String fingerprint = fingerprints.of(command);
                String cancellation = fingerprints.cancellationOf(command);
                var entry =
                    journal.read(
                        key,
                        command.targetVersion(),
                        session.read(journal.lookup(key, command.targetVersion())));
                var sourceRule = command.conversionSource();
                var conversion =
                    sourceRule == null
                        ? null
                        : new RecordReferenceCollectionConversion(
                            key, sourceRule, snapshot.conversion().binding());
                var rebind = command.collectionRebind() == null ? null : batches.rebind(key, command, snapshot.rebind());
                if (entry == null) {
                  if (cancel
                      && !validateUnstartedCancellation
                      && (!command.calculation().collectionsOrEmpty().isEmpty()
                          || !command.retiringCollections().isEmpty())) {
                    // No maintenance has started. Release all locks, then obtain the full
                    // declaration
                    // scope to journal a cancellation. Existing maintenance never needs its
                    // targets.
                    return null;
                  }
                  var previous =
                      calculations.read(
                          key,
                          command.expectedVersion(),
                          session.read(calculations.lookup(key, command.expectedVersion())));
                  if (!cancel) {
                    if (session.admission().revision() != command.expectedVersion()) {
                      throw conflict();
                    }
                    RecordCollectionRetirement.validateTransition(command, previous);
                    RecordReferenceCollectionConversion.requireTransition(command, previous);
                    requireEmptyRetirements(command, snapshot, key, session);
                  }
                  var identity =
                      previous == null
                          ? null
                          : new RecordPublicationIntent.InstalledCalculation(
                              previous.revision(),
                              RecordCalculationStore.policyChecksum(previous.policy()));
                  int intentVersion =
                      rebind != null ? 6 : conversion != null
                          ? 5
                          : !command.retiringCollections().isEmpty()
                              ? 4
                              : command.calculation().collectionsOrEmpty().isEmpty() ? 2 : 3;
                  RecordPublicationIntent.Conversion frozenConversion = null;
                  if (conversion != null) {
                    conversion.inspect(session);
                    frozenConversion =
                        new RecordPublicationIntent.Conversion(
                            snapshot.conversion().target(),
                            snapshot.conversion().entrance(),
                            conversion.activeCount(session));
                  }
                  RecordPublicationIntent.Rebind frozenRebind = null;
                  if (rebind != null) {
                    RecordCollectionRebind.requireInstalled(command, previous);
                    var scope = snapshot.rebind();
                    frozenRebind = new RecordPublicationIntent.Rebind(scope.source().target(),
                        scope.destination().target(), scope.destination().entrance(), rebind.inspect(session));
                  }
                  var intent =
                      new RecordPublicationIntent(
                          intentVersion,
                          key,
                          command,
                          fingerprint,
                          session.admission().fingerprint(),
                          actor.actorId(),
                          operationAt,
                          snapshot.bindings(),
                          identity,
                          snapshot.resources(),
                          java.util.Map.of(),
                          command.calculation().collectionsOrEmpty().isEmpty()
                              ? null
                              : snapshot.collectionBindings(),
                          command.retiringCollections().isEmpty()
                              ? null
                              : snapshot.retirementBindings(),
                          frozenConversion, frozenRebind);
                  intent = batches.protect(intent, snapshot.models(), previous);
                  entry = batches.start(intent, snapshot.models(), session, cancellation);
                  // Commit maintenance ownership before any potentially expensive calculation work.
                  return cancel
                      ? batches.requestCancellation(intent, cancellation, session)
                      : entry;
                }
                var intent = entry.intent();
                if (!intent.command().equals(command)
                    || !intent.fingerprint().equals(fingerprint)) {
                  throw conflict();
                }
                if (entry.checkpoint().progress() == null) {
                  return batches.start(intent, snapshot.models(), session, cancellation);
                }
                if (intent.contractVersion() < 2) {
                  throw new DnaRuntimeException(
                      DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
                      "Legacy publication maintenance has no trusted resource snapshot; explicit"
                          + " recovery is required");
                }
                // A delayed publish request must not overwrite restoration progress with a false
                // failure.
                if (!cancel
                    && entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.RESTORING) {
                  throw conflict();
                }
                attempted.set(entry);
                if (rebind != null) {
                  requireFrozenRebind(intent, snapshot.rebind(), cancel);
                }
                if (conversion != null) {
                  var frozen = intent.conversion();
                  var candidate = snapshot.conversion();
                  boolean targetChanged =
                      cancel
                          ? !frozen.target().target().equals(candidate.target().target())
                          : !frozen.target().equals(candidate.target());
                  if (targetChanged
                      || !intent
                          .collectionBindings()
                          .get(command.referenceConversion().destinationRelationId())
                          .equals(candidate.edge())
                      || !cancel && !frozen.entrance().equals(candidate.entrance())) {
                    throw RecordReferenceAdmission.incompatible();
                  }
                }
                if (!cancel
                    && (intent.collectionBindings() != null
                            && !intent.collectionBindings().equals(snapshot.collectionBindings())
                        || intent.retiringCollectionBindings() != null
                            && !intent
                                .retiringCollectionBindings()
                                .equals(snapshot.retirementBindings()))) {
                  throw new DnaRuntimeException(
                      DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
                      "Publication collection version or storage binding changed");
                }
                batches.validateMetadata(intent, snapshot.models());
                if (cancel
                    && entry.checkpoint().phase() != RecordPublicationCheckpoint.Phase.RESTORING) {
                  return batches.requestCancellation(intent, cancellation, session);
                }
                if (!cancel
                    && !command.retiringCollections().isEmpty()
                    && entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.APPLYING
                    && entry.checkpoint().applied() == entry.checkpoint().total()) {
                  requireEmptyRetirements(command, snapshot, key, session);
                }
                return batches.step(
                    intent,
                    snapshot.models(),
                    snapshot.historical(),
                    entry.checkpoint().sequence(),
                    10,
                    cancellation,
                    session,
                    conversion, rebind);
              });
      if (outcome == null && cancel && !validateUnstartedCancellation) {
        return advance(actor, command, true, true, deadline);
      }
      return java.util.Objects.requireNonNull(outcome, "publication outcome");
    } catch (RuntimeException failure) {
      var entry = attempted.get();
      if (entry != null) {
        var recorded = recordFailure(actor, command, entry, failure);
        if (failure instanceof RecordPublicationChecks.Rejected rejected
            && recorded != null
            && recorded.checkpoint().phase() == RecordPublicationCheckpoint.Phase.APPLYING
            && recorded.checkpoint().sequence() == entry.checkpoint().sequence() + 1
            && recorded.checkpoint().failureCode()
                == DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED) {
          throw new RecordPublicationChecks.Rejected(
              rejected.violations(), rejected.valueViolations(), recorded.checkpoint().progress());
        }
      }
      throw failure;
    }
  }

  private static void requireEmptyRetirements(RecordWritePolicyPublicationCommand command,
      RecordPublicationMetadata.Snapshot snapshot, RecordWriteFenceKey key, RecordPublicationSession session) {
    if (command.collectionRebind() == null) {
      RecordCollectionRetirement.requireEmpty(
          key, command.retiringCollections(), snapshot.retiringCollections(), session);
      return;
    }
    String rebindSource = command.collectionRebind().sourceRelationId();
    var other = command.retiringCollections().stream()
        .filter(rule -> !rule.relationId().equals(rebindSource)).toList();
    var edges = new java.util.HashMap<>(snapshot.retiringCollections());
    edges.remove(rebindSource);
    RecordCollectionRetirement.requireEmpty(key, other, edges, session);
  }

  private static void requireFrozenRebind(
      RecordPublicationIntent intent, RecordPublicationMetadata.Rebind scope, boolean cancel) {
    var frozen = intent.rebind();
    if (!sameTarget(frozen.sourceTarget(), scope.source().target(), cancel)
        || !sameTarget(frozen.destinationTarget(), scope.destination().target(), cancel)
        || !cancel && !frozen.entrance().equals(scope.destination().entrance())) {
      throw RecordReferenceAdmission.incompatible();
    }
    // Forward execution checks the complete binding maps. Cancellation resolves only the two edges.
    if (cancel
        && (!scope
                .source()
                .edge()
                .equals(
                    intent.retiringCollectionBindings().get(scope.source().binding().relationId()))
            || !scope
                .destination()
                .edge()
                .equals(
                    intent.collectionBindings().get(scope.destination().binding().relationId())))) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  private static boolean sameTarget(
      RecordPublicationIntent.Binding frozen,
      RecordPublicationIntent.Binding current,
      boolean cancel) {
    return cancel ? frozen.target().equals(current.target()) : frozen.equals(current);
  }

  /** Replays a committed retirement without resolving the former entrance or its edge schema. */
  private RecordPublicationStore.Entry retiredPublicationResult(
      RuntimeActor actor,
      RecordWritePolicyPublicationCommand command,
      RecordExecutionDeadline deadline) {
    return execute(
        actor,
        command,
        false,
        false,
        deadline,
        (snapshot, session) -> {
          var key = new RecordWriteFenceKey(actor.tenantId(), command.physicalModelId());
          var entry =
              journal.read(
                  key,
                  command.targetVersion(),
                  session.read(journal.lookup(key, command.targetVersion())));
          if (entry == null || entry.checkpoint().progress() != null) {
            return null;
          }
          if (!entry.intent().command().equals(command)
              || !entry.intent().fingerprint().equals(fingerprints.of(command))) {
            throw conflict();
          }
          return batches.step(
              entry.intent(),
              snapshot.models(),
              snapshot.historical(),
              entry.checkpoint().sequence(),
              10,
              fingerprints.cancellationOf(command),
              session);
        });
  }

  private RecordPublicationStore.Entry recordFailure(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final RecordPublicationStore.Entry attempted,
      final RuntimeException failure) {
    var code =
        failure instanceof DnaRuntimeException dna
            ? dna.errorCode()
            : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
    try {
      return execute(
          actor,
          command,
          false,
          (snapshot, session) ->
              batches.failed(
                  attempted.intent(),
                  attempted.checkpoint().sequence(),
                  code,
                  fingerprints.cancellationOf(command),
                  session));
    } catch (RuntimeException diagnosticFailure) {
      // The original error remains authoritative. Failure of diagnostics never changes the business
      // outcome.
      failure.addSuppressed(diagnosticFailure);
      return null;
    }
  }

  private <T> T execute(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final boolean validateReferences,
      final BiFunction<RecordPublicationMetadata.Snapshot, RecordPublicationSession, T> work) {
    return execute(
        actor,
        command,
        validateReferences,
        false,
        new RecordExecutionDeadline(Math.min(command.timeoutMs(), 30_000)),
        work);
  }

  private <T> T execute(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final boolean validateReferences,
      final boolean includeConversion,
      final RecordExecutionDeadline deadline,
      final BiFunction<RecordPublicationMetadata.Snapshot, RecordPublicationSession, T> work) {
    if (command.calculation() == null) {
      throw new IllegalArgumentException("Calculation publication requires an explicit definition");
    }
    var ids = new LinkedHashSet<String>();
    ids.add(command.physicalModelId());
    command.calculation().details().values().forEach(scope -> ids.add(scope.physicalModelId()));
    if (validateReferences) {
      command.calculation().referencesOrEmpty().forEach(rule -> ids.add(rule.targetModelId()));
      command
          .calculation()
          .collectionsOrEmpty()
          .forEach(
              rule -> {
                ids.add(rule.edgeModelId());
                ids.add(rule.targetModelId());
              });
      command
          .retiringCollections()
          .forEach(
              rule -> {
                ids.add(rule.edgeModelId());
                ids.add(rule.targetModelId());
              });
    }
    if (includeConversion) {
      java.util.stream.Stream.of(command.conversionDestination(), command.rebindSource(), command.rebindDestination())
          .filter(java.util.Objects::nonNull).forEach(rule -> {
            ids.add(rule.edgeModelId());
            ids.add(rule.targetModelId());
          });
    }
    return control.execute(
        actor.tenantId(),
        ids,
        deadline,
        locked -> {
          var snapshot =
              command.referenceConversion() != null || command.collectionRebind() != null
                  ? metadata.resolve(actor, command, locked, validateReferences, includeConversion)
                  : metadata.resolve(actor, command, locked, validateReferences);
          var root = snapshot.models().get(command.physicalModelId()).model();
          var children = new java.util.ArrayList<RuntimeRecordModel>();
          snapshot.models().values().stream()
              .map(RecordPublicationPatchCompiler.Resolved::model)
              .filter(model -> !model.physicalModelId().equals(root.physicalModelId()))
              .forEach(children::add);
          children.addAll(snapshot.retiringCollections().values());
          if (snapshot.rebind() != null) {
            var source = snapshot.rebind().source().binding();
            var destination = snapshot.rebind().destination().binding();
            for (var model : List.of(source.edge(), source.target(), destination.edge(), destination.target())) {
              if (!model.physicalModelId().equals(root.physicalModelId()) && children.stream()
                  .noneMatch(existing -> existing.physicalModelId().equals(model.physicalModelId()))) {
                children.add(model);
              }
            }
          } else if (snapshot.conversion() != null) {
            var conversion = snapshot.conversion().binding();
            children.add(conversion.edge());
            if (!conversion.target().physicalModelId().equals(root.physicalModelId())) {
              children.add(conversion.target());
            }
          }
          return backend.publication(
              root,
              children,
              new RecordWriteFenceKey(actor.tenantId(), root.physicalModelId()),
              deadline.remainingMillis(),
              session -> work.apply(snapshot, session));
        });
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT,
        "Publication identity or recovery direction changed");
  }
}
