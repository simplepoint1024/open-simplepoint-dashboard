package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Reads only reference identities through Calcite under the owning publication's maintenance fence.
 */
final class RecordPublicationLockDiscovery {
  private final RecordSqlCompiler sql;
  private final PhysicalSchemaCodec schemas;

  RecordPublicationLockDiscovery(RecordSqlCompiler sql, PhysicalSchemaCodec schemas) {
    this.sql = sql;
    this.schemas = schemas;
  }

  RecordPublicationLockPlan discover(
      RecordPublicationIntent intent,
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      List<RecordSqlStatement> statements,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var checks = new ArrayList<RecordReferenceCheck>();
    var verifiers = new ArrayList<Runnable>();
    for (var statement : statements) {
      var subject = statement.subject();
      if (subject == null
          || !intent.key().tenantId().equals(subject.tenantId())
          || !models.containsKey(subject.modelId())) {
        throw invalid();
      }
      var resolved = models.get(subject.modelId()).model();
      var model =
          new RuntimeRecordModel(
              resolved.physicalModelId(),
              resolved.appId(),
              resolved.formId(),
              resolved.activeVersion(),
              resolved.activeSchema(),
              resolved.target());
      if (model.activeSchema().references().isEmpty()) {
        continue;
      }
      var fields =
          model.activeSchema().references().stream()
              .map(
                  reference ->
                      model.activeSchema().fields().stream()
                          .filter(field -> field.fieldId().equals(reference.fieldId()))
                          .findFirst()
                          .orElseThrow(RecordPublicationLockDiscovery::invalid)
                          .code())
              .toList();
      var access =
          new RecordAccessContext(
              new RuntimeActor(intent.key().tenantId(), intent.actorId()),
              null,
              Set.copyOf(fields),
              Set.of(),
              Set.of(),
              null,
              null);
      var schema =
          new RecordSchemaReference(
              model.activeVersion(), schemas.encode(model.activeSchema()).checksum());
      var query =
          sql.lookup(
              model,
              access,
              new RecordLookupQuery(model.physicalModelId(), schema, statement.recordId(), fields));
      var observed = read(reader, query, model, access, statement.recordId());
      for (int index = 0; index < fields.size(); index++) {
        String target = RecordReferenceRules.identity(observed.values().get(fields.get(index)));
        if (target != null) {
          checks.add(
              new RecordReferenceCheck(
                  model.activeSchema().references().get(index).relationId(),
                  model.physicalModelId(),
                  observed.id(),
                  target,
                  false));
        }
      }
      verifiers.add(
          () -> {
            if (!observed.equals(read(reader, query, model, access, statement.recordId()))) {
              throw invalid();
            }
          });
    }
    return new RecordPublicationLockPlan(
        statements, checks, () -> verifiers.forEach(Runnable::run));
  }

  private static RecordView read(
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      RecordSelectPlan query,
      RuntimeRecordModel model,
      RecordAccessContext access,
      String id) {
    var result = reader.apply(query);
    if (result.truncated()) {
      throw invalid();
    }
    var rows = RecordQueryServiceImpl.views(result, query, model, access);
    if (rows.size() != 1 || !rows.getFirst().id().equals(id)) {
      throw invalid();
    }
    return rows.getFirst();
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Publication reference inputs are incomplete or changed while acquiring locks");
  }
}
