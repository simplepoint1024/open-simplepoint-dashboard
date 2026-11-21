package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.LINK;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.UNLINK;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.CollectionModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Physical transaction proof only: public collection capability/lifecycle admission is separate.
 */
class RecordCollectionSqlDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void calciteCommitsBothEndpointsWithTheEdgeAndRollsBackStaleVersions(boolean postgres)
      throws Exception {
    try (var fixture = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = install(fixture);
      var source = fixture.create(binding.source(), Map.of());
      var target = fixture.create(binding.target(), Map.of());
      fixture.save(binding.source(), source);
      fixture.save(binding.target(), target);
      var key =
          RecordCollectionEdgeKey.of(binding, "tenant-a", source.recordId(), target.recordId());
      execute(fixture, binding, key, 0, 0, RecordCollectionEdgeState.absent(), LINK);
      versions(fixture, binding, key, 1, 0, true);
      var lookup =
          fixture.backend.query(binding.edge(), RecordCollectionSql.lookup(binding, key), 30_000);
      assertThat(lookup.rows()).hasSize(1);
      var otherTenant =
          RecordCollectionEdgeKey.of(binding, "tenant-b", source.recordId(), target.recordId());
      assertThat(
              fixture
                  .backend
                  .query(binding.edge(), RecordCollectionSql.lookup(binding, otherTenant), 30_000)
                  .rows())
          .isEmpty();
      for (boolean restrictSource : List.of(false, true)) {
        var sourceAccess = fixture.access(binding.source());
        var targetAccess = fixture.access(binding.target());
        var now = Instant.now();
        var statements =
            List.of(
                fixture.compiler.touchCollectionEndpoint(
                    binding.source(),
                    restrictSource ? restricted(sourceAccess) : sourceAccess,
                    key.sourceId(),
                    1,
                    now),
                fixture.compiler.touchCollectionEndpoint(
                    binding.target(),
                    restrictSource ? targetAccess : restricted(targetAccess),
                    key.targetId(),
                    1,
                    now),
                RecordCollectionSql.change(
                    binding,
                    key,
                    new RecordCollectionEdgeState(0, true),
                    new CollectionEdgeMutation(key.targetId(), 1L, 0L, UNLINK),
                    sourceAccess,
                    now));
        assertThatThrownBy(
                () ->
                    fixture.backend.executeOwned(
                        binding.source(),
                        List.of(binding.target(), binding.edge()),
                        new RecordWriteAdmission("tenant-a", 0),
                        statements,
                        30_000,
                        null))
            .isInstanceOf(RecordMutationMissException.class);
        versions(fixture, binding, key, 1, 0, true);
      }
      // Source update succeeds first, then target CAS fails: neither source nor edge may commit.
      assertThatThrownBy(
              () ->
                  execute(
                      fixture, binding, key, 1, 0, new RecordCollectionEdgeState(0, true), UNLINK))
          .isInstanceOf(RecordMutationMissException.class);
      versions(fixture, binding, key, 1, 0, true);
      execute(fixture, binding, key, 1, 1, new RecordCollectionEdgeState(0, true), UNLINK);
      versions(fixture, binding, key, 2, 1, false);
      // An absent-edge observation cannot overwrite the retained identity with another INSERT.
      assertThatThrownBy(
              () -> execute(fixture, binding, key, 2, 2, RecordCollectionEdgeState.absent(), LINK))
          .isInstanceOf(DnaRuntimeException.class);
      versions(fixture, binding, key, 2, 1, false);
      execute(fixture, binding, key, 2, 2, new RecordCollectionEdgeState(1, false), LINK);
      versions(fixture, binding, key, 3, 2, true);
      // A delayed unlink from the prior generation fails after both endpoint updates; all roll
      // back.
      assertThatThrownBy(
              () ->
                  execute(
                      fixture, binding, key, 3, 3, new RecordCollectionEdgeState(0, true), UNLINK))
          .isInstanceOf(RecordMutationMissException.class);
      versions(fixture, binding, key, 3, 2, true);
      assertThatThrownBy(
              () ->
                  fixture.save(
                      binding.target(), fixture.delete(binding.target(), target.recordId(), 3)))
          .isInstanceOf(DnaRuntimeException.class);
      execute(fixture, binding, key, 3, 3, new RecordCollectionEdgeState(2, true), UNLINK);
      versions(fixture, binding, key, 4, 3, false);
      fixture.save(binding.target(), fixture.delete(binding.target(), target.recordId(), 4));
      assertThat(fixture.deleted(binding.target(), target.recordId())).isTrue();
      assertThat(fixture.count(binding.edge())).isEqualTo(1);
    }
  }

  static RecordAccessContext restricted(RecordAccessContext access) {
    return new RecordAccessContext(
        access.actor(),
        access.resourceCode(),
        access.readableFields(),
        access.writableFields(),
        access.maskedFields(),
        new org.simplepoint.core.datascopeannotation.DataScopeCondition(
            "SELF", "created_org_dept_id", "created_by", "other-user", java.util.Set.of()),
        access.organizationId());
  }

  static void execute(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      RecordCollectionEdgeKey key,
      long sourceVersion,
      long targetVersion,
      RecordCollectionEdgeState state,
      CollectionEdgeMutation.Mode mode) {
    Instant now = Instant.now();
    var source =
        fixture.compiler.touchCollectionEndpoint(
            binding.source(), fixture.access(binding.source()), key.sourceId(), sourceVersion, now);
    var target =
        fixture.compiler.touchCollectionEndpoint(
            binding.target(), fixture.access(binding.target()), key.targetId(), targetVersion, now);
    var edge =
        RecordCollectionSql.change(
            binding,
            key,
            state,
            new CollectionEdgeMutation(key.targetId(), targetVersion, state.version(), mode),
            fixture.access(binding.source()),
            now);
    var result =
        fixture.backend.executeOwned(
            binding.source(),
            List.of(binding.target(), binding.edge()),
            new RecordWriteAdmission("tenant-a", 0),
            List.of(source, target, edge),
            30_000,
            null);
    assertThat(result.pushedStatementCount()).isEqualTo(3);
  }

  private static void versions(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      RecordCollectionEdgeKey key,
      long endpointVersion,
      long edgeVersion,
      boolean active)
      throws Exception {
    assertThat(
            ((Number) fixture.value(binding.source(), key.sourceId(), "record_version"))
                .longValue())
        .isEqualTo(endpointVersion);
    assertThat(
            ((Number) fixture.value(binding.target(), key.targetId(), "record_version"))
                .longValue())
        .isEqualTo(endpointVersion);
    assertThat(((Number) fixture.value(binding.edge(), key.id(), "record_version")).longValue())
        .isEqualTo(edgeVersion);
    assertThat(fixture.value(binding.edge(), key.id(), binding.sourceColumn()))
        .isEqualTo(active ? key.sourceId() : null);
    assertThat(fixture.value(binding.edge(), key.id(), binding.targetColumn()))
        .isEqualTo(active ? key.targetId() : null);
    assertThat(fixture.value(binding.edge(), key.id(), "created_by")).isEqualTo("user");
    assertThat(fixture.value(binding.edge(), key.id(), "created_org_dept_id"))
        .isEqualTo("department");
    assertThat(fixture.deleted(binding.edge(), key.id())).isFalse();
  }

  static RecordCollectionBinding install(RecordReferenceIntegrityDatabaseTest.Fixture fixture)
      throws Exception {
    return install(fixture, false);
  }

  static RecordCollectionBinding install(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture, boolean self) throws Exception {
    return install(fixture, self, "members");
  }

  static RecordCollectionBinding install(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture, boolean self, String relationId)
      throws Exception {
    var source = fixture.customer;
    var suffix = UUID.randomUUID().toString().replace("-", "");
    var location = source.target().metadataTarget();
    String targetId = UUID.randomUUID().toString();
    var target =
        new RuntimeRecordModel(
            targetId,
            "app",
            relationId,
            1,
            new PhysicalSchemaDefinition("v1", List.of(), List.of(), null, true),
            new PhysicalSchemaTarget(
                targetId,
                new RuntimeMetadataTarget(
                    location.dataSourceId(),
                    location.catalogName(),
                    location.schemaName(),
                    "member_" + suffix)));
    target = self ? source : reserve(fixture, target);
    targetId = target.physicalModelId();
    String edgeId = "membership-" + suffix;
    var schema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(field("source"), field("target")),
            List.of(new LogicalIndexDefinition("pair", List.of("source_id", "target_id"), true)),
            null,
            true,
            List.of(
                new LogicalReferenceDefinition("left", "source", source.physicalModelId(), false),
                new LogicalReferenceDefinition("right", "target", targetId, false)),
            new CollectionModelRelation(relationId, "left", "right"));
    var endpoints = new java.util.LinkedHashMap<String, RuntimeMetadataTarget>();
    endpoints.put(source.physicalModelId(), location);
    endpoints.put(targetId, target.target().metadataTarget());
    var edge =
        new RuntimeRecordModel(
            edgeId,
            source.appId(),
            schema.collection().modelFormId(source.formId()),
            1,
            schema,
            new PhysicalSchemaTarget(
                edgeId,
                new RuntimeMetadataTarget(
                    location.dataSourceId(),
                    location.catalogName(),
                    location.schemaName(),
                    "edge_" + suffix),
                endpoints));
    edge = reserve(fixture, edge);
    for (var model : self ? List.of(edge) : List.of(target, edge)) {
      var plan =
          fixture.schemaBackend.planInitial(
              model.target(), "Collection transaction test", model.activeSchema());
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.createStatement()) {
        for (var step : plan.steps()) {
          statement.execute(step.statementSql());
        }
      }
      new RecordWriteFencePreparation(
              new RecordWriteFenceProvisioner(fixture.schemaBackend), fixture.backend)
          .prepare(new RuntimeActor("tenant-a", "publisher"), model.target());
    }
    return new RecordCollectionBinding(edge, source, target);
  }

  private static RuntimeRecordModel reserve(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture, RuntimeRecordModel model)
      throws Exception {
    var target =
        org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture
            .reserveNewModel(fixture.dataSource, model.target(), "tenant-a");
    return new RuntimeRecordModel(
        model.physicalModelId(),
        model.appId(),
        model.formId(),
        model.activeVersion(),
        model.activeSchema(),
        target);
  }

  private static LogicalFieldDefinition field(String id) {
    return new LogicalFieldDefinition(
        id, id + "_id", id, LogicalFieldType.UUID, true, null, null, null, null);
  }
}
