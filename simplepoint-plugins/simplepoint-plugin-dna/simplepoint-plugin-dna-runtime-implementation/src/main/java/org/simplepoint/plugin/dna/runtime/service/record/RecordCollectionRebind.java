package org.simplepoint.plugin.dna.runtime.service.record;

import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.quote;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Stages one old edge per item. The old foreign keys protect the targets until atomic cutover. */
final class RecordCollectionRebind {
  private final RecordWriteFenceKey owner;
  private final RecordCollectionBinding source;
  private final RecordCollectionBinding destination;
  private final RecordWritePolicyPublicationCommand command;
  private final RecordSqlCompiler sql;

  RecordCollectionRebind(
      RecordWriteFenceKey owner,
      RecordWritePolicyPublicationCommand command,
      RecordCollectionBinding source,
      RecordCollectionBinding destination,
      RecordSqlCompiler sql) {
    if (!owner.modelId().equals(source.source().physicalModelId())
        || !source.source().equals(destination.source())
        || !command.rebindSource().relationId().equals(source.relationId())
        || !command.rebindDestination().relationId().equals(destination.relationId())
        || source.edge().physicalModelId().equals(destination.edge().physicalModelId())
        || source.target().physicalModelId().equals(destination.target().physicalModelId())
        || source.target().appId().equals(destination.target().appId())
            && source.target().formId().equals(destination.target().formId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    this.owner = owner;
    this.command = command;
    this.source = source;
    this.destination = destination;
    this.sql = sql;
  }

  static void requireInstalled(
      RecordWritePolicyPublicationCommand command, RecordCalculationStore.Installed installed) {
    if (installed == null
        || !installed.policy().parentSchema().equals(command.schemaReference())
        || !withoutCollections(installed.policy().definition())
            .equals(withoutCollections(command.calculation()))
        || !installed.policy().definition().collectionsOrEmpty().contains(command.rebindSource())
        || installed.policy().definition().collectionsOrEmpty().stream()
            .anyMatch(
                rule ->
                    rule.relationId().equals(command.rebindDestination().relationId())
                        || rule.edgeModelId().equals(command.rebindDestination().edgeModelId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  private static RecordCalculationDefinition withoutCollections(
      RecordCalculationDefinition definition) {
    return new RecordCalculationDefinition(
        definition.languageVersion(),
        definition.formulas(),
        definition.details(),
        definition.constraints(),
        definition.validations(),
        definition.stateTransitions(),
        definition.writeRules(),
        definition.references(),
        null);
  }

  long inspect(RecordPublicationSession session) {
    requireNoRows(
        session.read(
            plan(
                "SELECT 1 FROM "
                    + table(source.edge())
                    + " WHERE \"tenant_id\" = ? AND ("
                    + eitherEndpoint(source)
                    + ") AND ("
                    + quote(source.sourceColumn())
                    + " IS NULL OR "
                    + quote(source.targetColumn())
                    + " IS NULL OR \"deleted_at\" IS NOT NULL OR \"record_version\" < 0 OR"
                    + " \"record_version\" >= "
                    + (Long.MAX_VALUE - 1)
                    + ") FETCH NEXT 1 ROWS ONLY",
                List.of(owner.tenantId()),
                1)));
    requireNoRows(
        session.read(
            plan(
                "SELECT 1 FROM "
                    + table(destination.edge())
                    + " WHERE \"tenant_id\" = ? FETCH NEXT 1 ROWS ONLY",
                List.of(owner.tenantId()),
                1)));
    return activeSourceCount(session);
  }

  long activeSourceCount(RecordPublicationSession session) {
    return count(source.edge(), " AND (" + eitherEndpoint(source) + ")", session);
  }

  long destinationCount(RecordPublicationSession session) {
    return count(destination.edge(), "", session);
  }

  private long count(RuntimeRecordModel model, String condition, RecordPublicationSession session) {
    return RecordPublicationRows.count(
        session.aggregate(
            plan(
                "SELECT COUNT(*) FROM " + table(model) + " WHERE \"tenant_id\" = ?" + condition,
                List.of(owner.tenantId()),
                1)));
  }

  List<Edge> page(
      String cursor, int limit, RecordPublicationIntent intent, RecordPublicationSession session) {
    var parameters = new RecordSqlParameters();
    String where =
        "\"tenant_id\" = "
            + parameters.add(owner.tenantId())
            + " AND "
            + quote(source.sourceColumn())
            + " IS NOT NULL"
            + " AND "
            + quote(source.targetColumn())
            + " IS NOT NULL AND \"deleted_at\" IS NULL";
    if (cursor != null) {
      RecordPublicationAggregate.requireId(cursor);
      where += " AND \"id\" > " + parameters.add(cursor);
    }
    var read =
        session.read(
            plan(
                "SELECT \"id\", "
                    + quote(source.sourceColumn())
                    + ", "
                    + quote(source.targetColumn())
                    + ", \"record_version\", \"created_org_dept_id\" FROM "
                    + table(source.edge())
                    + " WHERE "
                    + where
                    + " ORDER BY \"id\" ASC FETCH NEXT "
                    + limit
                    + " ROWS ONLY",
                parameters.values(),
                limit));
    verifyRows(read, limit);
    if (read.rows().isEmpty()) {
      return List.of();
    }
    var ids = new LinkedHashSet<String>();
    var edges = new ArrayList<Edge>();
    for (var row : read.rows()) {
      if (row.size() != 5
          || !(row.get(0) instanceof String id)
          || !ids.add(id)
          || id.equals(cursor)
          || !(row.get(1) instanceof String parent)
          || !(row.get(2) instanceof String oldTarget)
          || !(row.get(3) instanceof Number version)
          || row.get(4) != null && !(row.get(4) instanceof String)) {
        throw conflict();
      }
      String target = command.collectionRebind().targetRecords().get(oldTarget);
      if (target == null
          || !id.equals(
              RecordCollectionEdgeKey.of(source, owner.tenantId(), parent, oldTarget).id())
          || owner.modelId().equals(destination.target().physicalModelId())
              && parent.equals(target)) {
        throw conflict();
      }
      var edge =
          new Edge(
              id,
              parent,
              oldTarget,
              target,
              new BigDecimal(version.toString()).longValueExact(),
              (String) row.get(4));
      edges.add(edge);
    }
    var destinations = edges.stream().map(Edge::destinationRecordId).collect(Collectors.toSet());
    var aliases =
        command.collectionRebind().targetRecords().entrySet().stream()
            .filter(pair -> destinations.contains(pair.getValue()))
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getValue,
                    Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
    for (var edge : edges) {
      rejectDuplicate(edge, aliases.get(edge.destinationRecordId()), session);
    }
    qualify(edges, intent, session::read);
    return List.copyOf(edges);
  }

  private void rejectDuplicate(Edge edge, List<String> matching, RecordPublicationSession session) {
    if (matching.size() < 2) {
      return;
    }
    for (int start = 0; start < matching.size(); start += 100) {
      var args = new RecordSqlParameters();
      String where =
          "\"tenant_id\" = "
              + args.add(owner.tenantId())
              + " AND "
              + quote(source.sourceColumn())
              + " = "
              + args.add(edge.sourceRecordId())
              + " AND \"id\" <> "
              + args.add(edge.id());
      String targets =
          matching.subList(start, Math.min(start + 100, matching.size())).stream()
              .map(args::add)
              .collect(Collectors.joining(", "));
      requireNoRows(
          session.read(
              plan(
                  "SELECT 1 FROM "
                      + table(source.edge())
                      + " WHERE "
                      + where
                      + " AND "
                      + quote(source.targetColumn())
                      + " IN ("
                      + targets
                      + ") FETCH NEXT 1 ROWS ONLY",
                  args.values(),
                  1)));
    }
  }

  RecordPublicationLockPlan prepare(
      RecordPublicationIntent intent,
      List<Edge> edges,
      boolean restore,
      RecordPublicationSession session) {
    var statements = new ArrayList<RecordSqlStatement>();
    var references = new ArrayList<RecordReferenceCheck>();
    var collection = destination.edge().activeSchema().collection();
    for (var edge : edges) {
      var key =
          RecordCollectionEdgeKey.of(
              destination, owner.tenantId(), edge.sourceRecordId(), edge.destinationRecordId());
      if (restore) {
        statements.add(RecordCollectionSql.removeUnpublished(destination, key, intent));
      } else {
        statements.add(
            RecordCollectionSql.change(
                destination,
                key,
                RecordCollectionEdgeState.absent(),
                -1L,
                CollectionEdgeMutation.Mode.LINK,
                access(intent, edge.department()),
                intent.clock()));
        references.add(
            new RecordReferenceCheck(
                collection.sourceReferenceId(),
                destination.edge().physicalModelId(),
                key.id(),
                key.sourceId(),
                false));
        references.add(
            new RecordReferenceCheck(
                collection.targetReferenceId(),
                destination.edge().physicalModelId(),
                key.id(),
                key.targetId(),
                false));
      }
    }
    return new RecordPublicationLockPlan(
        statements,
        references,
        () -> {
          if (!restore) {
            verifyOriginal(edges, session);
            qualify(edges, intent, session::read);
          }
        });
  }

  private void verifyOriginal(List<Edge> edges, RecordPublicationSession session) {
    var keys =
        edges.stream()
            .map(
                edge ->
                    RecordCollectionEdgeKey.of(
                        source, owner.tenantId(), edge.sourceRecordId(), edge.originalRecordId()))
            .toList();
    var states =
        RecordCollectionEdgeReads.read(
            source, keys, session.read(RecordCollectionSql.lookup(source, keys)));
    for (var edge : edges) {
      var state = states.get(edge.id());
      if (state == null || !state.active() || state.version() != edge.version()) {
        throw conflict();
      }
    }
  }

  private void qualify(
      List<Edge> edges,
      RecordPublicationIntent intent,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var ids =
        edges.stream()
            .map(Edge::destinationRecordId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    var target = destination.target();
    var condition =
        RecordReferenceRules.validateTargetCondition(
            target, command.rebindDestination().eligibility());
    RecordCollectionObservationReader.versions(
        ids,
        reader.apply(
            sql.referenceTargets(target, access(intent, null), List.copyOf(ids), condition, true)
                .requirePhysicalRelations()));
  }

  /** Old endpoint columns and policy switch in the same physical transaction. */
  void retireSource(
      RecordPublicationIntent intent, long expected, RecordPublicationSession session) {
    if (expected > 0) {
      session.retireReboundEdges(source, intent.actorId(), intent.clock(), expected);
    }
  }

  private RecordAccessContext access(RecordPublicationIntent intent, String department) {
    var readable = new LinkedHashSet<String>(destination.target().requestedFieldCodes());
    readable.add("id");
    return new RecordAccessContext(
        new RuntimeActor(owner.tenantId(), intent.actorId()),
        null,
        readable,
        Set.of(),
        Set.of(),
        null,
        department);
  }

  private static String eitherEndpoint(RecordCollectionBinding edge) {
    return quote(edge.sourceColumn())
        + " IS NOT NULL OR "
        + quote(edge.targetColumn())
        + " IS NOT NULL";
  }

  private static RecordSelectPlan plan(String sql, List<Object> args, int limit) {
    return new RecordSelectPlan(sql, args, List.of(), limit).requirePhysicalRelations();
  }

  private static void requireNoRows(CalciteQueryResult result) {
    verifyRows(result, 1);
    if (!result.rows().isEmpty()) {
      throw conflict();
    }
  }

  private static void verifyRows(CalciteQueryResult result, int limit) {
    if (result.truncated()
        || result.returnedRows() != result.rows().size()
        || result.rows().size() > limit) {
      throw conflict();
    }
  }

  static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE,
        "Stored associations or target mappings conflict with collection replacement");
  }

  record Edge(
      String id,
      String sourceRecordId,
      String originalRecordId,
      String destinationRecordId,
      long version,
      String department) {
    Edge {
      RecordPublicationAggregate.requireId(id);
      RecordPublicationAggregate.requireId(sourceRecordId);
      RecordPublicationAggregate.requireId(originalRecordId);
      RecordPublicationAggregate.requireId(destinationRecordId);
      if (version < 0
          || version >= Long.MAX_VALUE - 1
          || department != null && department.length() > 255) {
        throw new IllegalArgumentException("Invalid rebound edge snapshot");
      }
    }
  }
}
