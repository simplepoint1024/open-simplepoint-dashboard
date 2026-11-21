package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationView;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

final class RecordCollectionService {
  private final RuntimeRequestContext context;
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy permissions;
  private final RecordSqlCompiler sql;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationAccess calculations;
  private final RecordRequestFingerprint fingerprints;
  private final RecordAdmissionController admissions;
  private final RecordAuditSupport audit;
  private final RecordReferenceCopyAuthorization copies;

  RecordCollectionService(
      RuntimeRequestContext context,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy permissions,
      RecordSqlCompiler sql,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationAccess calculations,
      RecordRequestFingerprint fingerprints,
      RecordAdmissionController admissions,
      RecordAuditSupport audit) {
    this.context = context;
    this.models = models;
    this.permissions = permissions;
    this.sql = sql;
    this.backend = backend;
    this.calculations = calculations;
    this.fingerprints = fingerprints;
    this.admissions = admissions;
    this.audit = audit;
    copies = new RecordReferenceCopyAuthorization(models, permissions, sql, backend, calculations);
  }

  RecordPageView page(CollectionRecordPageQuery query) {
    var deadline = new RecordExecutionDeadline(30_000);
    var source =
        source(query.sourceModelId(), query.sourceSchema(), query.relationId(), query.candidates());
    try (var permit = admissions.enter(source.access().actor().tenantId())) {
      var selected =
          resolve(
              source,
              query.relationId(),
              deadline,
              query.direction() == CollectionRecordPageQuery.Direction.FROM_SOURCE
                  ? query.records()
                  : null);
      var binding = selected.binding();
      boolean forward = query.direction() == CollectionRecordPageQuery.Direction.FROM_SOURCE;
      var result = forward ? binding.target() : binding.source();
      var request = query.records();
      if (!result.physicalModelId().equals(request.physicalModelId())
          || result.requestedVersion() != request.schemaReference().version()) {
        throw RecordReferenceAdmission.incompatible();
      }
      var resolved =
          models.resolve(
              source.access().actor(), request.physicalModelId(), request.schemaReference());
      if (!resolved.activeSchema().equals(result.activeSchema())
          || !resolved.requestedSchema().equals(result.requestedSchema())) {
        throw RecordReferenceAdmission.incompatible();
      }
      var captured =
          backend.readCollection(
              binding,
              source.access(),
              selected.target(),
              selected.rule(),
              deadline.remainingMillis(),
              (sourcePolicy, targetPolicy, reader) -> {
                var sourceFields =
                    calculations
                        .resolve(binding.source(), source.access(), sourcePolicy)
                        .apply(binding.source(), source.access());
                var targetFields =
                    calculations
                        .resolveReference(binding.target(), selected.target(), targetPolicy)
                        .apply(binding.target(), selected.target());
                var resultFields = forward ? targetFields : sourceFields;
                RecordValueCondition anchorCondition = null;
                RecordPageQuery page = request;
                if (query.candidates()) {
                  var sourceCondition =
                      RecordReferenceRules.validateTargetCondition(
                          binding.source(), selected.rule().mutableWhen());
                  var targetCondition =
                      RecordReferenceRules.validateTargetCondition(
                          binding.target(), selected.rule().eligibility());
                  anchorCondition = forward ? sourceCondition : targetCondition;
                  var candidateCondition = forward ? targetCondition : sourceCondition;
                  page =
                      new RecordPageQuery(
                          request.physicalModelId(),
                          request.schemaReference(),
                          request.page(),
                          request.size(),
                          request.fields(),
                          request.filter(),
                          request.sorts(),
                          both(request.condition(), candidateCondition));
                }
                var selection =
                    new RecordCollectionSelection(
                        binding,
                        RecordCollectionSelection.Direction.valueOf(query.direction().name()),
                        query.anchorRecordId(),
                        forward ? sourceFields : targetFields,
                        anchorCondition,
                        query.candidates());
                var plan = sql.collectionPage(result, resultFields, page, selection);
                var rows =
                    RecordQueryServiceImpl.views(reader.apply(plan), plan, result, resultFields);
                long count =
                    RecordQueryServiceImpl.countValue(
                        reader.apply(sql.collectionCount(result, resultFields, page, selection)));
                var projection =
                    RecordCurrentCopyRead.capture(
                        result,
                        resultFields,
                        List.of(),
                        rows.stream().map(row -> new OwnedRecordView(row, Map.of())).toList(),
                        forward ? targetPolicy : sourcePolicy,
                        reader);
                return new RecordRelatedQueries.Page(
                    new RecordPageView(page.page(), page.size(), count, rows), projection);
              });
      return new RecordPageView(
          request.page(),
          request.size(),
          captured.page().total(),
          captured.copies().authorize(copies, deadline).stream()
              .map(OwnedRecordView::parent)
              .toList());
    }
  }

  CollectionRecordObservationView observe(CollectionRecordObservationQuery query) {
    var deadline = new RecordExecutionDeadline(30_000);
    var source = source(query.sourceModelId(), query.sourceSchema(), query.relationId(), true);
    try (var permit = admissions.enter(source.access().actor().tenantId())) {
      var selected = resolve(source, query.relationId(), deadline);
      var binding = selected.binding();
      requireDistinctEndpoints(binding, query.sourceRecordId(), query.targetRecordIds());
      return backend.readCollection(
          binding,
          source.access(),
          selected.target(),
          selected.rule(),
          deadline.remainingMillis(),
          (sourcePolicy, targetPolicy, reader) -> {
            var sourceFields =
                calculations
                    .resolve(binding.source(), source.access(), sourcePolicy)
                    .apply(binding.source(), source.access());
            var targetFields =
                calculations
                    .resolveReference(binding.target(), selected.target(), targetPolicy)
                    .apply(binding.target(), selected.target());
            var sourcePlan =
                sql.referenceTargets(
                    binding.source(),
                    sourceFields,
                    List.of(query.sourceRecordId()),
                    RecordReferenceRules.validateTargetCondition(
                        binding.source(), selected.rule().mutableWhen()),
                    true);
            long sourceVersion =
                RecordCollectionObservationReader.versions(
                        Set.of(query.sourceRecordId()), reader.apply(sourcePlan))
                    .get(query.sourceRecordId());
            var ids = query.targetRecordIds();
            var targetPlan = sql.referenceTargets(binding.target(), targetFields, ids, null, true);
            var versions =
                RecordCollectionObservationReader.versions(
                    Set.copyOf(ids), reader.apply(targetPlan));
            var keys =
                ids.stream()
                    .map(
                        id ->
                            RecordCollectionEdgeKey.of(
                                binding,
                                source.access().actor().tenantId(),
                                query.sourceRecordId(),
                                id))
                    .toList();
            var states =
                RecordCollectionEdgeReads.read(
                    binding, keys, reader.apply(RecordCollectionSql.lookup(binding, keys)));
            return new CollectionRecordObservationView(
                sourceVersion,
                keys.stream()
                    .map(
                        key -> {
                          var state = states.get(key.id());
                          return new CollectionRecordObservationView.Edge(
                              key.targetId(),
                              versions.get(key.targetId()),
                              state.version(),
                              state.active());
                        })
                    .toList());
          });
    }
  }

  CollectionRecordResult receipt(CollectionRecordCommand command) {
    var source =
        source(command.sourceModelId(), command.sourceSchema(), command.relationId(), true);
    var actor = source.access().actor();
    var operation = RecordOperationIdentity.of(actor, command, fingerprints.of(command));
    try (var permit = admissions.enter(actor.tenantId())) {
      return backend.findCollectionResult(
          source.model(), source.access(), operation, command, command.timeoutMs());
    }
  }

  CollectionRecordResult execute(CollectionRecordCommand command) {
    var source =
        source(command.sourceModelId(), command.sourceSchema(), command.relationId(), true);
    var actor = source.access().actor();
    var operation = RecordOperationIdentity.of(actor, command, fingerprints.of(command));
    var deadline = new RecordExecutionDeadline(command.timeoutMs());
    long startedAt = System.nanoTime();
    try (var permit = admissions.enter(actor.tenantId())) {
      var existing =
          backend.findCollectionResult(
              source.model(), source.access(), operation, command, deadline.remainingMillis());
      if (existing != null) {
        return existing;
      }
      var selected = resolve(source, command.relationId(), deadline);
      requireDistinctEndpoints(
          selected.binding(),
          command.sourceRecordId(),
          command.mutations().stream()
              .map(
                  org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation
                      ::targetRecordId)
              .toList());
      var outcome =
          backend.executeCollection(
              selected.binding(),
              source.access(),
              selected.target(),
              selected.rule(),
              command,
              operation,
              calculations,
              sql);
      audit.success(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_SUCCEEDED,
          source.model(),
          source.access(),
          outcome.edges().stream().filter(CollectionRecordResult.Edge::changed).count(),
          (System.nanoTime() - startedAt) / 1_000_000);
      return outcome;
    }
  }

  private Source source(
      String modelId, RecordSchemaReference schema, String relationId, boolean write) {
    var actor = context.requireActor();
    var model = models.resolve(actor, modelId, schema);
    var access = permissions.authorize(actor, model);
    var fields = AuthorizationContextHolder.getContext().fieldPermissionsFor(access.resourceCode());
    String field =
        RecordPermissionModel.key(modelId)
            + "#"
            + RecordPermissionModel.collectionField(relationId);
    String grant = fields == null ? null : fields.get(field);
    var authority = AuthorizationContextHolder.getContext();
    if (access.resourceGeneration() < 1
        || write
            && !Boolean.TRUE.equals(authority.getIsAdministrator())
            && !authority.getResources().contains(access.resourceCode() + ".records.edit")
        || grant != null && !"EDITABLE".equals(grant) && (write || !"VISIBLE".equals(grant))) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, "The collection relation is unavailable");
    }
    return new Source(model, access);
  }

  private Selected resolve(Source source, String relationId, RecordExecutionDeadline deadline) {
    return resolve(source, relationId, deadline, null);
  }

  private Selected resolve(
      Source source,
      String relationId,
      RecordExecutionDeadline deadline,
      RecordPageQuery targetQuery) {
    var rule =
        backend.read(
            source.model(),
            List.of(),
            source.access(),
            deadline.remainingMillis(),
            session ->
                RuntimeCalciteRecordBackend.collectionRule(session.calculation(), relationId));
    var actor = source.access().actor();
    if (targetQuery != null && !rule.targetModelId().equals(targetQuery.physicalModelId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    var target =
        source.model().physicalModelId().equals(rule.targetModelId())
            ? source.model()
            : targetQuery == null
                ? models.resolveCurrent(actor, rule.targetModelId())
                : models.resolve(actor, rule.targetModelId(), targetQuery.schemaReference());
    var access = permissions.authorizeReference(actor, target, rule.targetResourceCode());
    var edge = models.resolve(actor, rule.edgeModelId(), rule.edgeSchema());
    return new Selected(
        RecordCollectionPublication.validate(rule, source.model(), edge, target), rule, access);
  }

  private static void requireDistinctEndpoints(
      RecordCollectionBinding binding, String id, List<String> targets) {
    if (binding.source().physicalModelId().equals(binding.target().physicalModelId())
        && targets.contains(id)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, "A record cannot be linked to itself");
    }
  }

  private static RecordValueCondition both(
      RecordValueCondition first, RecordValueCondition second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return new RecordValueCondition(
        RecordValueConditionOperator.AND, null, List.of(), List.of(first, second));
  }

  private record Source(RuntimeRecordModel model, RecordAccessContext access) {}

  private record Selected(
      RecordCollectionBinding binding, RecordCollectionRule rule, RecordAccessContext target) {}
}
