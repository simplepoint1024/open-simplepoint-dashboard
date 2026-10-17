package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
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
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
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
  private final RecordQueryService queries;
  private final RecordRequestFingerprint fingerprints;
  private final RecordAdmissionController admission;
  private final RecordAuditSupport audit;
  private final RecordExecutionObserver observer;

  /**
   * Creates the owned-record boundary with explicit authorization and transaction collaborators.
   */
  public OwnedRecordService(
      RuntimeRequestContext context,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordQueryService queries,
      RecordRequestFingerprint fingerprints,
      RecordAdmissionController admission,
      RecordAuditSupport audit,
      RecordExecutionObserver observer) {
    this.context = context;
    this.models = models;
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.queries = queries;
    this.fingerprints = fingerprints;
    this.admission = admission;
    this.audit = audit;
    this.observer = observer;
  }

  /** Reads an authorized parent and its owned child collections with consistent versions. */
  public OwnedRecordView get(OwnedRecordQuery query) {
    var actor = context.requireActor();
    var parent =
        models.resolve(actor, query.parent().physicalModelId(), query.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children = children(parent, access, query.details());
    RecordView before = queries.get(query.parent());
    Map<String, List<RecordView>> values = new LinkedHashMap<>();
    try (var ignored = admission.enter(actor.tenantId())) {
      for (var child : children) {
        values.put(child.model().physicalModelId(), rows(child, before.id()));
      }
    }
    RecordView after =
        queries.get(
            new RecordLookupQuery(
                parent.physicalModelId(),
                query.parent().schemaReference(),
                before.id(),
                List.of("id")));
    if (before.version() != after.version()) {
      throw conflict();
    }
    return new OwnedRecordView(before, values);
  }

  /** Returns the original confirmation for an exact tenant, actor and request identity. */
  public RecordCommandResult findResult(OwnedRecordCommand command) {
    var prepared = prepare(command);
    validateIntent(prepared, command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      return receipt(prepared, command);
    }
  }

  /** Snapshot reads verify the exact operation owner and reapply current field visibility. */
  public OwnedRecordView snapshot(OwnedRecordCommand command) {
    if (command.snapshot() == null) {
      throw invalid("This operation did not request a snapshot");
    }
    var prepared = prepare(command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var committed = receipt(prepared, command);
      if (committed == null) {
        return null;
      }
      var captured =
          backend.snapshot(prepared.model(), prepared.operation(), command.parent().timeoutMs());
      if (captured == null
          || !captured.parent().id().equals(committed.recordId())
          || captured.parent().version() != committed.version()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Committed snapshot is absent or inconsistent");
      }
      Map<String, List<RecordView>> details = new LinkedHashMap<>();
      for (var child : prepared.snapshotChildren()) {
        var rows = captured.details().get(child.model().physicalModelId());
        if (rows == null) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Snapshot collection is absent");
        }
        details.put(
            child.model().physicalModelId(),
            rows.stream()
                .map(row -> snapshotProjection(row, child.access(), child.model()))
                .toList());
      }
      return new OwnedRecordView(
          snapshotProjection(captured.parent(), prepared.access(), prepared.model()), details);
    }
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
    var prepared = prepare(command);
    long started = System.nanoTime();
    try {
      var result = executeAuthorized(prepared, command);
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

  private RecordCommandResult executeAuthorized(Prepared prepared, OwnedRecordCommand command) {
    validateIntent(prepared, command);
    try (var ignored = admission.enter(prepared.access().actor().tenantId())) {
      var committed = receipt(prepared, command);
      if (committed != null) {
        return committed;
      }
      try {
        var mutation = command.parent().mutations().getFirst();
        String parentId = parentId(prepared, command);
        boolean create = mutation instanceof RecordCreateMutation;
        boolean delete = mutation instanceof RecordDeleteMutation;
        if (!create) {
          var current =
              queries.get(
                  new RecordLookupQuery(
                      prepared.model().physicalModelId(),
                      command.parent().schemaReference(),
                      parentId,
                      List.of("id")));
          long expected =
              mutation instanceof RecordUpdateMutation update
                  ? update.expectedVersion()
                  : ((RecordDeleteMutation) mutation).expectedVersion();
          if (current.version() != expected) {
            throw conflict();
          }
        }
        Instant now = Instant.now();
        List<RecordSqlStatement> statements = new ArrayList<>();
        statements.add(
            compiler.mutation(
                prepared.model(), prepared.access(), mutation, now, create ? parentId : null));
        int total = 0;
        for (int index = 0; index < prepared.children().size(); index++) {
          Child child = prepared.children().get(index);
          var requested = command.details().get(index).rows();
          List<RecordView> existing = create ? List.of() : rows(child, parentId);
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
        var catalogChildren = new LinkedHashMap<String, RuntimeRecordModel>();
        prepared
            .children()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        prepared
            .snapshotChildren()
            .forEach(child -> catalogChildren.put(child.model().physicalModelId(), child.model()));
        var snapshot =
            command.snapshot() == null
                ? null
                : snapshotPlan(
                    prepared,
                    command.parent().schemaReference(),
                    parentId,
                    statements.getFirst().resultVersion());
        var result =
            backend.executeOwned(
                prepared.model(),
                List.copyOf(catalogChildren.values()),
                new RecordWriteAdmission(
                    prepared.access().actor().tenantId(), command.parent().writePolicyVersion(), prepared.access().resourceGeneration()),
                statements,
                command.parent().timeoutMs(),
                prepared.operation(),
                snapshot);
        if (result.replayedResults() != null) {
          return result.replayedResults().getFirst();
        }
        var first = statements.getFirst();
        return new RecordCommandResult(
            first.mutationType(), first.recordId(), first.resultVersion(), 1, first.updatedAt());
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

  private Prepared prepare(OwnedRecordCommand command) {
    var actor = context.requireActor();
    var parent =
        models.resolve(
            actor, command.parent().physicalModelId(), command.parent().schemaReference());
    var access = policies.authorize(actor, parent);
    var children =
        children(parent, access, command.details().stream().map(OwnedRecordSet::model).toList());
    var operation = RecordOperationIdentity.of(actor, command.parent(), fingerprints.of(command));
    var snapshotChildren =
        command.snapshot() == null
            ? List.<Child>of()
            : children(parent, access, command.snapshot().details());
    return new Prepared(parent, access, children, snapshotChildren, operation);
  }

  private RecordSnapshotPlan snapshotPlan(
      Prepared prepared, RecordSchemaReference reference, String parentId, long version) {
    List<RecordSnapshotPlan.Part> parts = new ArrayList<>();
    var model = prepared.model();
    var fields =
        projectionFields(model, prepared.access());
    if (fields.isEmpty()) {
      fields = List.of("id");
    }
    for (int offset = 0; offset < fields.size(); offset += 90) {
      var plan =
          compiler.lookup(
              model,
              prepared.access(),
              new RecordLookupQuery(
                  model.physicalModelId(),
                  reference,
                  parentId,
                  fields.subList(offset, Math.min(fields.size(), offset + 90))));
      parts.add(new RecordSnapshotPlan.Part(model, prepared.access(), plan, true));
    }
    for (var child : prepared.snapshotChildren()) {
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
      parts.add(
          new RecordSnapshotPlan.Part(
              child.model(),
              child.access(),
              compiler.page(child.model(), child.access(), query),
              false));
    }
    return new RecordSnapshotPlan(parentId, version, List.copyOf(parts));
  }

  private List<Child> children(
      RuntimeRecordModel parent, RecordAccessContext access, List<OwnedRecordModel> references) {
    List<Child> result = new ArrayList<>();
    for (var reference : references) {
      var model =
          models.resolve(access.actor(), reference.physicalModelId(), reference.schemaReference());
      var owner = model.activeSchema().owner();
      var rootTarget = parent.target().metadataTarget();
      var childTarget = model.target().metadataTarget();
      if (owner == null
          || !owner.parentModelId().equals(parent.physicalModelId())
          || !Objects.equals(owner, model.requestedSchema().owner())
          || !model.appId().equals(parent.appId())
          || !rootTarget.dataSourceId().equals(childTarget.dataSourceId())
          || !Objects.equals(rootTarget.catalogName(), childTarget.catalogName())
          || !Objects.equals(rootTarget.schemaName(), childTarget.schemaName())) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            "Child does not belong to this aggregate target");
      }
      String parentCode =
          model.requestedSchema().fields().stream()
              .filter(field -> field.fieldId().equals(owner.parentFieldId()))
              .findFirst()
              .orElseThrow(() -> invalid("Parent reference is absent"))
              .code();
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
              permission.organizationId(), permission.resourceGeneration());
      result.add(new Child(model, childAccess, reference.schemaReference(), parentCode));
    }
    return result;
  }

  private void validateIntent(Prepared prepared, OwnedRecordCommand command) {
    String parentId = parentId(prepared, command);
    var mutation = command.parent().mutations().getFirst();
    compiler.mutation(
        prepared.model(),
        prepared.access(),
        mutation,
        Instant.EPOCH,
        mutation instanceof RecordCreateMutation ? parentId : null);
    for (int index = 0; index < prepared.children().size(); index++) {
      var child = prepared.children().get(index);
      for (var row : command.details().get(index).rows()) {
        if (row.values().containsKey(child.parentCode()) || row.values().containsKey("row_order")) {
          throw invalid("Child ownership and order are server controlled");
        }
        var values = new LinkedHashMap<>(row.values());
        values.put(child.parentCode(), parentId);
        values.put("row_order", 0);
        compiler.mutation(
            child.model(),
            child.access(),
            row.id() == null
                ? new RecordCreateMutation("child", values)
                : new RecordUpdateMutation("child", row.id(), row.expectedVersion(), values),
            Instant.EPOCH);
      }
    }
  }

  private static List<String> projectionFields(RuntimeRecordModel model, RecordAccessContext access) {
    return java.util.stream.Stream.concat(model.requestedFieldCodes().stream(),
        org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.storageCodes().stream())
        .filter(access.readableFields()::contains).distinct().toList();
  }

  private List<RecordView> rows(Child child, String parentId) {
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
        RecordQueryServiceImpl.views(
            backend.query(child.model(), plan, 30_000), plan, child.model(), child.access());
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

  private record Prepared(
      RuntimeRecordModel model,
      RecordAccessContext access,
      List<Child> children,
      List<Child> snapshotChildren,
      RecordOperationIdentity operation) {}
}
