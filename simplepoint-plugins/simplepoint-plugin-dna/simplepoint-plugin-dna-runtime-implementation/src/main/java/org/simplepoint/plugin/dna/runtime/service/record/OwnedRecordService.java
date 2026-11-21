package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationView;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.stereotype.Service;

/**
 * Parent-authorized, bounded aggregate CRUD. Child tables have no independent public write path.
 */
@Service
public class OwnedRecordService {
  private final RuntimeRequestContext context;
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy policies;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordRequestFingerprint fingerprints;
  private final RecordAdmissionController admission;
  private final RecordAuditSupport audit;
  private final RecordExecutionObserver observer;
  private final RecordCalculationWriteSupport calculations;
  private final RecordCalculationAccess calculationAccess;
  private final RecordReferenceSnapshotReader referenceSnapshots;
  private final RecordReferenceCopyAuthorization referenceCopies;
  private final RecordReferenceMappingTargets mappingTargets;
  private final RecordReferenceMappingRead mappingReads;
  private final RecordCollectionService collections;

  /**
   * Creates the owned-record boundary with explicit authorization and transaction collaborators.
   */
  public OwnedRecordService(
      RuntimeRequestContext context,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordRequestFingerprint fingerprints,
      RecordAdmissionController admission,
      RecordAuditSupport audit,
      RecordExecutionObserver observer,
      RecordCalculationWriteSupport calculations,
      RecordCalculationAccess calculationAccess,
      PhysicalSchemaCodec schemas) {
    this.context = context;
    this.models = models;
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.fingerprints = fingerprints;
    this.admission = admission;
    this.audit = audit;
    this.observer = observer;
    this.calculations = calculations;
    this.calculationAccess = calculationAccess;
    this.referenceSnapshots =
        new RecordReferenceSnapshotReader(models, policies, compiler, backend, calculationAccess);
    this.referenceCopies =
        new RecordReferenceCopyAuthorization(
            models, policies, compiler, backend, calculationAccess);
    this.mappingTargets =
        new RecordReferenceMappingTargets(
            policies, compiler, backend, calculations, calculationAccess, referenceCopies, schemas);
    this.collections = new RecordCollectionService(
        context, models, policies, compiler, backend, calculationAccess, fingerprints, admission, audit);
    this.mappingReads =
        new RecordReferenceMappingRead(models, policies, backend, calculations, mappingTargets);
  }

  /** Pages distinct parents through a root or owned-detail reference installed by publication. */
  public org.simplepoint.plugin.dna.runtime.api.record.RecordPageView pageRelated(
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery query) {
    var deadline = new RecordExecutionDeadline(30_000);
    var actor = context.requireActor();
    var parent =
        models.resolve(actor, query.records().physicalModelId(), query.records().schemaReference());
    var access = policies.authorize(actor, parent);
    long started = System.nanoTime();
    try (var ignored = admission.enter(actor.tenantId())) {
      var result =
          new RecordRelatedQueries(
                  models, policies, compiler, backend, calculations, calculationAccess)
              .page(parent, access, query, deadline);
      deadline.remainingMillis();
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.success(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType.RECORD_QUERIED,
          parent,
          access,
          result.records().size(),
          duration);
      observer.record("related-page", "succeeded", parent, access, duration);
      return result;
    } catch (RuntimeException failure) {
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.failure(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType.RECORD_QUERIED,
          parent,
          access,
          duration,
          failure instanceof DnaRuntimeException dnaFailure
              ? dnaFailure.errorCode()
              : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED);
      observer.record("related-page", "failed", parent, access, duration);
      throw failure;
    }
  }

  /** Reads an authorized parent and its owned child collections with consistent versions. */
  public OwnedRecordView get(OwnedRecordQuery query) {
    var deadline = new RecordExecutionDeadline(30_000);
    var actor = context.requireActor();
    var parent =
        models.resolve(actor, query.parent().physicalModelId(), query.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children = children(parent, access, query.details());
    try (var ignored = admission.enter(actor.tenantId())) {
      return captureCurrent(query, parent, access, children, deadline)
          .authorize(referenceCopies, deadline)
          .getFirst();
    }
  }

  private RecordCurrentCopyRead captureCurrent(
      OwnedRecordQuery query,
      RuntimeRecordModel parent,
      RecordAccessContext access,
      List<Child> children,
      RecordExecutionDeadline deadline) {
    return backend.read(
        parent,
        children.stream().map(Child::model).toList(),
        access,
        deadline.remainingMillis(),
        session -> {
          var rules = calculationAccess.resolve(parent, access, session.calculation());
          var visible = rules.apply(parent, access);
          RecordView before = lookup(parent, visible, query.parent(), session.query());
          Map<String, List<RecordView>> values = new LinkedHashMap<>();
          for (var child : children) {
            var restricted =
                new Child(
                    child.model(),
                    rules.apply(child.model(), child.access()),
                    child.reference(),
                    child.parentCode());
            values.put(
                child.model().physicalModelId(), rows(restricted, before.id(), session.query()));
          }
          var after =
              lookup(
                  parent,
                  visible,
                  new RecordLookupQuery(
                      parent.physicalModelId(),
                      query.parent().schemaReference(),
                      before.id(),
                      List.of("id")),
                  session.query());
          if (before.version() != after.version()) {
            throw conflict();
          }
          return RecordCurrentCopyRead.capture(
              parent,
              visible,
              children.stream().map(Child::model).toList(),
              List.of(new OwnedRecordView(before, values)),
              session.calculation(),
              session.query());
        });
  }

  /**
   * Original entrances for currently readable fields; no row identities or values leave this call.
   */
  public java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget>
      currentTargets(
          org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery query) {
    var deadline = new RecordExecutionDeadline(query.timeoutMs());
    var actor = context.requireActor();
    var parent = models.resolve(actor, query.physicalModelId(), query.schemaReference());
    var access = policies.authorize(actor, parent);
    try (var ignored = admission.enter(actor.tenantId())) {
      RecordCurrentCopyRead captured;
      if (query.record() != null) {
        captured =
            captureCurrent(
                query.record(),
                parent,
                access,
                children(parent, access, query.record().details()),
                deadline);
      } else if (query.related() != null) {
        captured =
            new RecordRelatedQueries(
                    models, policies, compiler, backend, calculations, calculationAccess)
                .capture(parent, access, query.related(), deadline)
                .copies();
      } else {
        captured =
            new RecordCurrentPageRead(compiler, backend, calculationAccess)
                .capture(parent, access, query.page(), deadline);
      }
      return captured.targets(referenceCopies, deadline);
    }
  }

  /** Observes installed mapping values at an authorized target without granting future writes. */
  public org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingView observeMapping(
      org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery query) {
    var actor = context.requireActor();
    try (var ignored = admission.enter(actor.tenantId())) {
      return mappingReads.read(actor, query);
    }
  }

  /** Previews the exact confirmed mapping intent without writing values or receipts. */
  public OwnedRecordPreview previewMapped(MappedOwnedRecordCommand request) {
    return preview(request.command(), request);
  }

  /**
   * Previews authorized intent under the current policy and resource fences. The borrowed read
   * session exposes no write operation; validation and calculation share the commit preparation.
   */
  public OwnedRecordPreview preview(final OwnedRecordCommand command) {
    return preview(command, null);
  }

  private OwnedRecordPreview preview(OwnedRecordCommand command, MappedOwnedRecordCommand mapped) {
    var deadline = new RecordExecutionDeadline(command.parent().timeoutMs());
    if (command.snapshot() != null
        || command.parent().mutations().getFirst() instanceof RecordDeleteMutation) {
      throw invalid("Preview accepts create or update intent without a snapshot");
    }
    var prepared = prepare(command, true, mapped == null ? null : mapped.relatedScope(), mapped);
    validateIntent(prepared, command, mapped);
    var scopes = calculationScopes(prepared);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var mapping = loadMappings(prepared, mapped, deadline);
      var related = prepared.related();
      var guards =
          related == null
              ? List.<RecordResourceGuard>of()
              : List.of(
                  new RecordRelatedTargetRead(
                          models, policies, compiler, backend, calculationAccess)
                      .guard(
                          related.selection().source(),
                          related.selection().access(),
                          related.selection().rule(),
                          related.scope().targetRecordId(),
                          deadline));
      var captured =
          backend.read(
              prepared.model(),
              scopes.values().stream().map(RecordCalculationWriteSupport.Scope::model).toList(),
              prepared.access(),
              guards,
              deadline.remainingMillis(),
              session -> {
                if (session.revision() != command.parent().writePolicyVersion()) {
                  throw new DnaRuntimeException(
                      DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT,
                      "Write policy changed before preview admission");
                }
                verifyCalculation(prepared, session.calculation());
                var rules =
                    calculationAccess.resolve(
                        prepared.model(), prepared.access(), session.calculation());
                return calculations.previewMapped(
                    prepared.calculation(),
                    new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()),
                    scopes,
                    command,
                    parentId(prepared, command),
                    prepared.operation(),
                    session.query(),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                    rules,
                    session.calculation(),
                    mapping,
                    related);
              });
      var visible = captured.authorize(prepared.access().actor(), referenceCopies, deadline);
      Map<String, List<Map<String, Object>>> details = new LinkedHashMap<>();
      visible
          .details()
          .forEach(
              (model, rows) -> details.put(model, rows.stream().map(RecordView::values).toList()));
      return new OwnedRecordPreview(
          visible.parent().values(), details, command.parent().writePolicyVersion());
    }
  }

  private RecordView lookup(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordLookupQuery query,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var plan = compiler.lookup(model, access, query);
    var values = RecordQueryServiceImpl.views(reader.apply(plan), plan, model, access);
    if (values.isEmpty()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the active data scope");
    }
    return values.getFirst();
  }

  /** Returns the original confirmation for an exact tenant, actor and request identity. */
  public RecordCommandResult findResult(OwnedRecordCommand command) {
    var prepared = prepare(command);
    validateIntent(prepared, command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return receipt(prepared, command);
    }
  }

  /** Reads the snapshot bound to the complete confirmed mapping intent. */
  public OwnedRecordView snapshotMapped(MappedOwnedRecordCommand request) {
    return snapshot(request.command(), request.relatedScope(), request);
  }

  /** Snapshot reads verify the exact operation owner and reapply current field visibility. */
  public OwnedRecordView snapshot(OwnedRecordCommand command) {
    return snapshot(command, null);
  }

  private OwnedRecordView snapshot(
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope) {
    return snapshot(command, scope, null);
  }

  private OwnedRecordView snapshot(
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      MappedOwnedRecordCommand mapped) {
    return readSnapshot(command, scope, mapped, null, this::authorizeSnapshot);
  }

  /**
   * Trusted APP participants identify a frozen operation; all projections use the current actor.
   * This method is deliberately absent from browser record controllers.
   */
  public OwnedRecordView snapshotForParticipant(
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query) {
    return readSnapshot(
        query.command(), query.relatedScope(), query.mapped(), query, this::authorizeSnapshot);
  }

  /** Discovers only original resource identities required by the current source projection. */
  public java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget>
      snapshotTargets(org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query) {
    return readSnapshot(
        query.command(),
        query.relatedScope(),
        query.mapped(),
        query,
        projection -> projection.values().targets());
  }

  private OwnedRecordView authorizeSnapshot(SnapshotProjection projection) {
    var copied =
        projection.values().authorize(projection.actor(), referenceCopies, projection.deadline());
    return referenceSnapshots.authorize(projection.actor(), copied, projection.deadline());
  }

  private <T> T readSnapshot(
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      MappedOwnedRecordCommand mapped,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery history,
      java.util.function.Function<SnapshotProjection, T> projection) {
    if (command.snapshot() == null) {
      throw invalid("This operation did not request a snapshot");
    }
    // The request identifies the business operation, never the formula used by its old snapshot.
    var deadline =
        new RecordExecutionDeadline(
            history == null ? command.parent().timeoutMs() : history.timeoutMs());
    var prepared = historical(prepare(command, false, scope, mapped), history);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var visible =
          backend.read(
              prepared.model(),
              List.of(),
              prepared.access(),
              deadline.remainingMillis(),
              session -> {
                var receipt = session.receipt(prepared.operation());
                if (receipt == null) {
                  return null;
                }
                var stored = session.snapshot(prepared.operation());
                if (stored == null
                    || receipt.size() != 1
                    || !stored.values().parent().id().equals(receipt.getFirst().recordId())
                    || stored.values().parent().version() != receipt.getFirst().version()) {
                  throw new DnaRuntimeException(
                      DnaRuntimeErrorCode.PERSISTENCE_FAILED,
                      "Committed snapshot is absent or inconsistent");
                }
                if (history != null
                    && (!history.recordId().equals(stored.values().parent().id())
                        || history.recordVersion() != stored.values().parent().version())) {
                  throw RecordReferenceSnapshotProvenance.invalid();
                }
                var installed = session.historical(stored);
                var historical =
                    calculationAccess.resolve(prepared.model(), prepared.access(), installed);
                var current =
                    calculationAccess.resolve(
                        prepared.model(), prepared.access(), session.calculation());
                var captured = stored.values();
                Map<String, List<RecordView>> details = new LinkedHashMap<>();
                for (var child : prepared.snapshotChildren()) {
                  var rows = captured.details().get(child.model().physicalModelId());
                  if (rows == null) {
                    throw new DnaRuntimeException(
                        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Snapshot collection is absent");
                  }
                  var access =
                      historical.apply(child.model(), current.apply(child.model(), child.access()));
                  details.put(
                      child.model().physicalModelId(),
                      rows.stream()
                          .map(row -> snapshotProjection(row, access, child.model()))
                          .toList());
                }
                var parentAccess =
                    historical.apply(
                        prepared.model(), current.apply(prepared.model(), prepared.access()));
                var projected =
                    new OwnedRecordView(
                        snapshotProjection(captured.parent(), parentAccess, prepared.model()),
                        details);
                var labels =
                    RecordReferenceSnapshotProvenance.visible(
                        prepared.model(),
                        prepared.snapshotChildren().stream().map(Child::model).toList(),
                        projected,
                        captured.referenceSnapshots(),
                        installed);
                var values = new OwnedRecordView(projected.parent(), projected.details(), labels);
                if (stored.copyOrigins() == null) {
                  return new RecordSnapshotRead(
                      RecordReferenceCopyRead.ordinary(values), stored.labelOrigins());
                }
                var bindings = new ArrayList<RecordCalculationWriteSupport.Binding>();
                var oldBinding =
                    calculations.bind(prepared.access().actor(), prepared.model(), installed);
                if (oldBinding != null) {
                  bindings.add(oldBinding);
                }
                if (!java.util.Objects.equals(installed, session.calculation())) {
                  var currentBinding =
                      calculations.bind(
                          prepared.access().actor(), prepared.model(), session.calculation());
                  if (currentBinding != null) {
                    bindings.add(currentBinding);
                  }
                }
                return new RecordSnapshotRead(
                    new RecordReferenceCopyRead(
                        values,
                        stored.copyOrigins(),
                        new RecordReferenceCopyProjection(
                            prepared.model(),
                            prepared.snapshotChildren().stream().map(Child::model).toList(),
                            RecordReferenceCopyProjection.dependencies(bindings)),
                        prepared.model(),
                        parentAccess),
                    stored.labelOrigins());
              });
      if (visible == null) {
        if (history != null) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        return null;
      }
      return projection.apply(new SnapshotProjection(prepared.access().actor(), visible, deadline));
    }
  }

  /**
   * Historical values retain the related intent fingerprint, without rereading mutable membership.
   */
  public OwnedRecordView snapshotRelated(
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand request) {
    return snapshot(request.command(), request.scope());
  }

  private static RecordView snapshotProjection(
      RecordView row, RecordAccessContext access, RuntimeRecordModel model) {
    Map<String, Object> values = new LinkedHashMap<>();
    row.values()
        .forEach(
            (field, value) -> {
              if (access.readableFields().contains(field)) {
                values.put(
                    field,
                    access.maskedFields().contains(field) && value != null
                        ? "***"
                        : "***".equals(value)
                            ? value
                            : RecordWireValues.wireValue(model, field, value));
              }
            });
    return new RecordView(row.id(), row.version(), values);
  }

  /** Commits the parent and child mutations, receipt and optional snapshot atomically. */
  public RecordCommandResult execute(OwnedRecordCommand command) {
    return executeAggregate(command, null);
  }

  /** Commits a relation-scoped aggregate using the same locks, policy and durable receipt path. */
  public RecordCommandResult executeRelated(
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand request) {
    return executeAggregate(request.command(), request.scope());
  }

  /** Recovers an exact intent without requiring a successfully deleted record to still exist. */
  public RecordCommandResult findRelatedResult(
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand request) {
    var prepared = prepare(request.command(), true, request.scope());
    validateIntent(prepared, request.command());
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return receipt(prepared, request.command());
    }
  }

  /** Commits confirmed copies, calculations, provenance and the aggregate atomically. */
  public RecordCommandResult executeMapped(MappedOwnedRecordCommand request) {
    return executeAggregate(request.command(), request.relatedScope(), request);
  }

  /** Recovers only the receipt of the exact confirmed mapping intent. */
  public RecordCommandResult findMappedResult(MappedOwnedRecordCommand request) {
    var prepared = prepare(request.command(), true, request.relatedScope(), request);
    validateIntent(prepared, request.command(), request);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return receipt(prepared, request.command());
    }
  }

  private RecordCommandResult executeAggregate(
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope) {
    return executeAggregate(command, scope, null);
  }

  private RecordCommandResult executeAggregate(
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      MappedOwnedRecordCommand mapped) {
    var prepared = prepare(command, true, scope, mapped);
    long started = System.nanoTime();
    try {
      var result = executeAuthorized(prepared, command, mapped);
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.success(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType
              .RECORD_TRANSACTION_SUCCEEDED,
          prepared.model(),
          prepared.access(),
          result.affectedRows(),
          duration);
      observer.record(
          "owned-transaction", "succeeded", prepared.model(), prepared.access(), duration);
      return result;
    } catch (RuntimeException failure) {
      long duration = (System.nanoTime() - started) / 1_000_000;
      audit.failure(
          org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType
              .RECORD_TRANSACTION_FAILED,
          prepared.model(),
          prepared.access(),
          duration,
          failure instanceof DnaRuntimeException dnaFailure
              ? dnaFailure.errorCode()
              : DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED);
      observer.record("owned-transaction", "failed", prepared.model(), prepared.access(), duration);
      throw failure;
    }
  }

  private RecordCommandResult executeAuthorized(
      Prepared prepared, OwnedRecordCommand command, MappedOwnedRecordCommand mapped) {
    var deadline = new RecordExecutionDeadline(command.parent().timeoutMs());
    validateIntent(prepared, command, mapped);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var committed = receipt(prepared, command);
      if (committed != null) {
        return committed;
      }
      try {
        var catalogChildren = new LinkedHashMap<String, RuntimeRecordModel>();
        prepared
            .children()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        prepared
            .snapshotChildren()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        prepared
            .calculationChildren()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        var mapping = loadMappings(prepared, mapped, deadline);
        var result =
            backend.executePreparedOwned(
                prepared.model(),
                List.copyOf(catalogChildren.values()),
                new RecordWriteAdmission(
                    prepared.access().actor().tenantId(),
                    command.parent().writePolicyVersion(),
                    prepared.access().resourceGeneration()),
                deadline.remainingMillis(),
                prepared.operation(),
                (reader, at, installed) -> {
                  var binding = prepared.calculation();
                  verifyCalculation(prepared, installed);
                  var write =
                      binding == null
                          ? prepareWrite(prepared, command, reader, at)
                          : calculatedWrite(prepared, command, reader, at, mapping);
                  return prepared.related() == null
                      ? write
                      : prepared
                          .related()
                          .verify(
                              prepared.model(),
                              prepared.access(),
                              command,
                              parentId(prepared, command),
                              write,
                              calculationScopes(prepared),
                              compiler,
                              reader);
                });
        return result.results().getFirst();
      } catch (RuntimeException failure) {
        try {
          var recovered = receipt(prepared, command);
          if (recovered != null) {
            return recovered;
          }
        } catch (DnaRuntimeException recovery) {
          if (recovery.errorCode() == DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT) {
            throw recovery;
          }
          failure.addSuppressed(recovery);
        }
        if (failure instanceof RecordMutationMissException) {
          throw conflict();
        }
        throw failure;
      }
    }
  }

  private RecordWritePreparation calculatedWrite(
      final Prepared prepared,
      final OwnedRecordCommand command,
      final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Instant at,
      RecordReferenceMappingWrite mapping) {
    var aggregate =
        mapping == null
            ? calculations.prepare(
                prepared.calculation(),
                new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()),
                calculationScopes(prepared),
                command,
                parentId(prepared, command),
                prepared.operation(),
                reader,
                at)
            : calculations.prepareMapped(
                prepared.calculation(),
                new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()),
                calculationScopes(prepared),
                command,
                parentId(prepared, command),
                prepared.operation(),
                reader,
                at,
                mapping);
    var statements = aggregate.statements();
    var snapshot =
        command.snapshot() == null
            ? null
            : snapshotPlan(
                prepared,
                command.parent().schemaReference(),
                parentId(prepared, command),
                statements.getFirst().resultVersion(),
                command.snapshot().requirements());
    return new RecordWritePreparation(
        statements, snapshot, 1, aggregate.references(), null, aggregate.copies());
  }

  private RecordReferenceMappingWrite loadMappings(
      Prepared prepared, MappedOwnedRecordCommand command, RecordExecutionDeadline deadline) {
    if (command == null
        || command.command().parent().mutations().getFirst() instanceof RecordDeleteMutation) {
      return null;
    }
    if (prepared.calculation() == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    var scopes = calculationScopes(prepared);
    var plan =
        backend.referencePlan(
            prepared.model(),
            scopes.values().stream().map(RecordCalculationWriteSupport.Scope::model).toList(),
            new RecordWriteAdmission(
                prepared.access().actor().tenantId(),
                command.command().parent().writePolicyVersion(),
                prepared.access().resourceGeneration()),
            deadline.remainingMillis());
    if (plan == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    scopes.put(
        prepared.model().physicalModelId(),
        new RecordCalculationWriteSupport.Scope(prepared.model(), prepared.access()));
    return RecordReferenceMappingWrite.load(
        plan,
        command,
        scopes,
        (target, ids, fields) -> mappingTargets.read(target, ids, fields, deadline));
  }

  private static void verifyCalculation(
      final Prepared prepared, final RecordCalculationStore.Installed installed) {
    if (prepared.calculation() != null) {
      prepared.calculation().verify(installed);
    } else if (installed != null && !installed.policy().definition().isEmpty()) {
      throw invalid("Calculation metadata is absent from prepared intent");
    }
  }

  private static Map<String, RecordCalculationWriteSupport.Scope> calculationScopes(
      final Prepared prepared) {
    Map<String, RecordCalculationWriteSupport.Scope> scopes = new LinkedHashMap<>();
    prepared
        .calculationChildren()
        .forEach(
            child ->
                scopes.put(
                    child.model().physicalModelId(),
                    new RecordCalculationWriteSupport.Scope(child.model(), child.access())));
    // Request-facing schemas take precedence so stable identities map historical payloads
    // correctly.
    prepared
        .children()
        .forEach(
            child ->
                scopes.put(
                    child.model().physicalModelId(),
                    new RecordCalculationWriteSupport.Scope(child.model(), child.access())));
    return scopes;
  }

  private RecordWritePreparation prepareWrite(
      Prepared prepared,
      OwnedRecordCommand command,
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      Instant now) {
    var mutation = command.parent().mutations().getFirst();
    String parentId = parentId(prepared, command);
    boolean create = mutation instanceof RecordCreateMutation;
    boolean delete = mutation instanceof RecordDeleteMutation;
    if (!create) {
      var lookup =
          compiler.lookup(
              prepared.model(),
              prepared.access(),
              new RecordLookupQuery(
                  prepared.model().physicalModelId(),
                  command.parent().schemaReference(),
                  parentId,
                  List.of("id")));
      var currentRows =
          RecordQueryServiceImpl.views(
              reader.apply(lookup), lookup, prepared.model(), prepared.access());
      if (currentRows.isEmpty()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the authorized scope");
      }
      var current = currentRows.getFirst();
      long expected =
          mutation instanceof RecordUpdateMutation update
              ? update.expectedVersion()
              : ((RecordDeleteMutation) mutation).expectedVersion();
      if (current.version() != expected) {
        throw conflict();
      }
    }
    List<RecordSqlStatement> statements = new ArrayList<>();
    statements.add(
        compiler.mutation(
            prepared.model(), prepared.access(), mutation, now, create ? parentId : null));
    int total = 0;
    for (int index = 0; index < prepared.children().size(); index++) {
      Child child = prepared.children().get(index);
      var requested = command.details().get(index).rows();
      List<RecordView> existing = create ? List.of() : rows(child, parentId, reader);
      if (delete && !requested.isEmpty()) {
        throw invalid("Delete cannot introduce child rows");
      }
      total += delete ? existing.size() : requested.size();
      Map<String, RecordView> byId = new LinkedHashMap<>();
      existing.forEach(row -> byId.put(row.id(), row));
      for (int rowIndex = 0; rowIndex < requested.size(); rowIndex++) {
        var row = requested.get(rowIndex);
        var values = new LinkedHashMap<>(row.values());
        values.put(child.parentCode(), parentId);
        values.put("row_order", rowIndex);
        if (row.id() == null) {
          String id =
              RecordRequestFingerprint.deterministicRecordId(
                  prepared.access().actor().tenantId(),
                  child.model().physicalModelId(),
                  prepared.operation().id() + ":" + rowIndex);
          statements.add(
              compiler.mutation(
                  child.model(),
                  child.access(),
                  new RecordCreateMutation("child", values),
                  now,
                  id));
        } else {
          var old = byId.remove(row.id());
          if (old == null || old.version() != row.expectedVersion()) {
            throw conflict();
          }
          statements.add(
              compiler.mutation(
                  child.model(),
                  child.access(),
                  new RecordUpdateMutation("child", row.id(), row.expectedVersion(), values),
                  now));
        }
      }
      for (var removed : byId.values()) {
        statements.add(
            compiler.mutation(
                child.model(),
                child.access(),
                new RecordDeleteMutation("child", removed.id(), removed.version()),
                now));
      }
    }
    if (total > 200 || statements.size() > 401) {
      throw invalid("Aggregate row budget exceeded");
    }
    var snapshot =
        command.snapshot() == null
            ? null
            : snapshotPlan(
                prepared,
                command.parent().schemaReference(),
                parentId,
                statements.getFirst().resultVersion(),
                command.snapshot().requirements());
    return new RecordWritePreparation(statements, snapshot);
  }

  private Prepared prepare(OwnedRecordCommand command) {
    return prepare(command, true);
  }

  private Prepared prepare(OwnedRecordCommand command, boolean includeCalculation) {
    return prepare(command, includeCalculation, null);
  }

  private Prepared prepare(
      OwnedRecordCommand command,
      boolean includeCalculation,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope) {
    return prepare(command, includeCalculation, scope, null);
  }

  private Prepared prepare(
      OwnedRecordCommand command,
      boolean includeCalculation,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      MappedOwnedRecordCommand mapped) {
    var actor = context.requireActor();
    var parent =
        models.resolve(
            actor, command.parent().physicalModelId(), command.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children =
        children(parent, access, command.details().stream().map(OwnedRecordSet::model).toList());
    var operation =
        RecordOperationIdentity.of(
            actor,
            command.parent(),
            mapped != null
                ? fingerprints.of(mapped)
                : scope == null
                    ? fingerprints.of(command)
                    : fingerprints.of(
                        new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand(
                            command, scope)));
    var snapshotChildren =
        command.snapshot() == null
            ? List.<Child>of()
            : children(parent, access, command.snapshot().details());
    var installed =
        includeCalculation
            ? backend.calculation(
                parent,
                actor.tenantId(),
                command.parent().writePolicyVersion(),
                command.parent().timeoutMs())
            : null;
    var calculation = calculations.bind(actor, parent, installed);
    var calculationChildren =
        calculation == null
            ? List.<Child>of()
            : children(
                parent,
                access,
                installed.policy().definition().details().values().stream()
                    .map(
                        detailSchema ->
                            new OwnedRecordModel(
                                detailSchema.physicalModelId(), detailSchema.schemaReference()))
                    .toList());
    return new Prepared(
        parent,
        access,
        children,
        snapshotChildren,
        operation,
        calculation,
        calculationChildren,
        scope == null || !includeCalculation
            ? null
            : RecordRelatedWritePlan.compile(
                parent, access, calculation, scope, policies, calculationAccess));
  }

  private RecordSnapshotPlan snapshotPlan(
      Prepared prepared,
      RecordSchemaReference reference,
      String parentId,
      long version,
      List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements) {
    List<RecordSnapshotPlan.Part> parts = new ArrayList<>();
    var model = prepared.model();
    var rules =
        calculationAccess.resolve(
            model,
            prepared.access(),
            prepared.calculation() == null ? null : prepared.calculation().installed());
    var parentAccess = rules.apply(model, prepared.access());
    var fields = projectionFields(model, parentAccess);
    if (fields.isEmpty()) {
      fields = List.of("id");
    }
    for (int offset = 0; offset < fields.size(); offset += 90) {
      var plan =
          compiler.lookup(
              model,
              parentAccess,
              new RecordLookupQuery(
                  model.physicalModelId(),
                  reference,
                  parentId,
                  fields.subList(offset, Math.min(fields.size(), offset + 90))));
      parts.add(new RecordSnapshotPlan.Part(model, parentAccess, plan, true));
    }
    for (var child : prepared.snapshotChildren()) {
      var childAccess = rules.apply(child.model(), child.access());
      var query =
          new RecordPageQuery(
              child.model().physicalModelId(),
              child.reference(),
              0,
              41,
              projectionFields(child.model(), childAccess),
              new RecordFilter(
                  RecordFilterOperator.EQUALS, child.parentCode(), List.of(parentId), List.of()),
              List.of(new RecordSort("row_order", RecordSortDirection.ASC)));
      parts.add(
          new RecordSnapshotPlan.Part(
              child.model(), childAccess, compiler.page(child.model(), childAccess, query), false));
    }
    return new RecordSnapshotPlan(
        parentId,
        version,
        List.copyOf(parts),
        new RecordSnapshotValidation(model, parentAccess, requirements));
  }

  private List<Child> children(
      RuntimeRecordModel parent, RecordAccessContext access, List<OwnedRecordModel> references) {
    List<Child> result = new ArrayList<>();
    for (var reference : references) {
      var model =
          models.resolve(access.actor(), reference.physicalModelId(), reference.schemaReference());
      String parentCode = OwnedRecordBinding.parentCode(parent, model);
      var permission = policies.owned(access.actor(), model, access);
      Set<String> readable = new LinkedHashSet<>(permission.readableFields());
      Set<String> writable = new LinkedHashSet<>(permission.writableFields());
      readable.addAll(Set.of(parentCode, "row_order"));
      writable.addAll(Set.of(parentCode, "row_order"));
      var childAccess =
          new RecordAccessContext(
              permission.actor(),
              permission.resourceCode(),
              readable,
              writable,
              permission.maskedFields(),
              permission.rowScope(),
              permission.organizationId(),
              permission.resourceGeneration(),
              permission.queryProtectedFields());
      result.add(new Child(model, childAccess, reference.schemaReference(), parentCode));
    }
    return result;
  }

  private void validateIntent(Prepared prepared, OwnedRecordCommand command) {
    validateIntent(prepared, command, null);
  }

  private void validateIntent(
      Prepared prepared, OwnedRecordCommand command, MappedOwnedRecordCommand mapped) {
    String parentId = parentId(prepared, command);
    var mutation = command.parent().mutations().getFirst();
    if (prepared.calculation() == null) {
      compiler.mutation(
          prepared.model(),
          prepared.access(),
          mutation,
          Instant.EPOCH,
          mutation instanceof RecordCreateMutation ? parentId : null);
    } else {
      compiler.validateIntent(
          prepared.model(),
          prepared.access(),
          mutation,
          prepared.calculation().controlledParentFields(),
          RecordReferenceMappingIntent.defaults(
              prepared.calculation(), mapped, prepared.model().physicalModelId(), null));
    }
    for (int index = 0; index < prepared.children().size(); index++) {
      var child = prepared.children().get(index);
      var rows = command.details().get(index).rows();
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        var row = rows.get(rowIndex);
        if (row.values().containsKey(child.parentCode()) || row.values().containsKey("row_order")) {
          throw invalid("Child ownership and order are server controlled");
        }
        var values = new LinkedHashMap<>(row.values());
        values.put(child.parentCode(), parentId);
        values.put("row_order", 0);
        var mutationRow =
            row.id() == null
                ? new RecordCreateMutation("child", values)
                : new RecordUpdateMutation("child", row.id(), row.expectedVersion(), values);
        if (prepared.calculation() == null) {
          compiler.mutation(child.model(), child.access(), mutationRow, Instant.EPOCH);
        } else {
          compiler.validateIntent(
              child.model(),
              child.access(),
              mutationRow,
              prepared.calculation().outputs(child.model().physicalModelId()),
              RecordReferenceMappingIntent.defaults(
                  prepared.calculation(), mapped, child.model().physicalModelId(), rowIndex));
        }
      }
    }
  }

  private static List<String> projectionFields(
      RuntimeRecordModel model, RecordAccessContext access) {
    return java.util.stream.Stream.concat(
            model.requestedFieldCodes().stream(),
            org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.storageCodes().stream())
        .filter(access.readableFields()::contains)
        .distinct()
        .toList();
  }

  private List<RecordView> rows(
      Child child, String parentId, Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var query =
        new RecordPageQuery(
            child.model().physicalModelId(),
            child.reference(),
            0,
            41,
            projectionFields(child.model(), child.access()),
            new RecordFilter(
                RecordFilterOperator.EQUALS, child.parentCode(), List.of(parentId), List.of()),
            List.of(new RecordSort("row_order", RecordSortDirection.ASC)));
    var plan = compiler.page(child.model(), child.access(), query);
    var rows =
        RecordQueryServiceImpl.views(reader.apply(plan), plan, child.model(), child.access());
    if (rows.size() > 40) {
      throw invalid("Stored detail row budget exceeded");
    }
    return rows;
  }

  private RecordCommandResult receipt(Prepared prepared, OwnedRecordCommand command) {
    var results =
        backend.findResult(prepared.model(), prepared.operation(), command.parent().timeoutMs());
    return results == null ? null : results.getFirst();
  }

  private String parentId(Prepared prepared, OwnedRecordCommand command) {
    return switch (command.parent().mutations().getFirst()) {
      case RecordCreateMutation ignored ->
          RecordRequestFingerprint.deterministicRecordId(
              prepared.access().actor().tenantId(),
              prepared.model().physicalModelId(),
              "owned:" + command.parent().idempotencyKey());
      case RecordUpdateMutation update -> update.recordId();
      case RecordDeleteMutation delete -> delete.recordId();
    };
  }

  private static DnaRuntimeException invalid(String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
        "Aggregate record changed or a detail row is not owned by this record");
  }

  private record Child(
      RuntimeRecordModel model,
      RecordAccessContext access,
      RecordSchemaReference reference,
      String parentCode) {}

  private static Prepared historical(
      Prepared prepared,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery history) {
    if (history == null) {
      return prepared;
    }
    if (!history.appId().equals(prepared.model().appId())
        || !history.formId().equals(prepared.model().formId())
        || !history.resourceCode().equals(prepared.access().resourceCode())) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    var operation = prepared.operation();
    // Change only the receipt owner to match the immutable accepted operation. Access, resource
    // fences, field projection and every target policy stay bound to the actual viewer.
    var original =
        new RecordOperationIdentity(
            operation.id(),
            operation.tenantId(),
            history.operationActorId(),
            operation.modelId(),
            operation.key(),
            operation.fingerprint());
    return new Prepared(
        prepared.model(),
        prepared.access(),
        prepared.children(),
        prepared.snapshotChildren(),
        original,
        prepared.calculation(),
        prepared.calculationChildren(),
        prepared.related());
  }

  private record SnapshotProjection(
      org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor actor,
      RecordSnapshotRead values,
      RecordExecutionDeadline deadline) {}

  private record Prepared(
      RuntimeRecordModel model,
      RecordAccessContext access,
      List<Child> children,
      List<Child> snapshotChildren,
      RecordOperationIdentity operation,
      RecordCalculationWriteSupport.Binding calculation,
      List<Child> calculationChildren,
      RecordRelatedWritePlan related) {}

  public RecordPageView pageCollection(CollectionRecordPageQuery request) {
    return collections.page(request);
  }

  public CollectionRecordObservationView observeCollection(CollectionRecordObservationQuery request) {
    return collections.observe(request);
  }

  public CollectionRecordResult executeCollection(CollectionRecordCommand request) {
    return collections.execute(request);
  }

  public CollectionRecordResult findCollectionResult(CollectionRecordCommand request) {
    return collections.receipt(request);
  }
}
