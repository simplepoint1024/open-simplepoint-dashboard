package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordDeleteTransaction;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.core.JdbcTemplate;

class OwnedRecordServiceDatabaseTest {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final OwnedRecordModel CHILD = new OwnedRecordModel("child-1", SCHEMA);

  @Test
  void createsUpdatesDeletesAndReplaysOneAggregate() throws Exception {
    var service = fixture();
    var create =
        command(
            "create",
            new RecordCreateMutation("parent", Map.of("title", "Parent")),
            List.of(new OwnedRecordRow(null, null, Map.of("title", "First"))));
    var created = service.execute(create);
    assertThat(service.execute(create)).isEqualTo(created);
    var current = service.get(query(created.recordId()));
    var row = current.details().get("child-1").getFirst();
    assertThat(row.values()).containsEntry("title", "First");
    var update =
        command(
            "update",
            new RecordUpdateMutation("parent", created.recordId(), 0, Map.of("title", "Updated")),
            List.of(
                new OwnedRecordRow(row.id(), row.version(), Map.of("title", "Edited")),
                new OwnedRecordRow(null, null, Map.of("title", "Second"))));
    assertThat(service.execute(update).version()).isEqualTo(1);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).hasSize(2);
    service.execute(
        command("delete", new RecordDeleteMutation("parent", created.recordId(), 1), List.of()));
    assertThat(service.findResult(update).version()).isEqualTo(1);
    assertThatThrownBy(() -> service.get(query(created.recordId())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void batchDeleteRollsBackEveryParentAndDetailOnStaleVersionAndReplaysSuccess() throws Exception {
    var database = databaseFixture();
    var first = database.service().execute(command("batch-first",
        new RecordCreateMutation("parent", Map.of("title", "First")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "First detail")))));
    var second = database.service().execute(command("batch-second",
        new RecordCreateMutation("parent", Map.of("title", "Second")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "Second detail")))));
    var firstDelete = new RecordDeleteMutation("first", first.recordId(), first.version());
    var stale = new OwnedRecordDeleteTransaction(new RecordTransactionCommand("model-1", SCHEMA,
        List.of(firstDelete, new RecordDeleteMutation("second", second.recordId(), second.version() + 1)),
        30_000, "batch-stale"), List.of(CHILD));
    assertThatThrownBy(() -> database.transactions().deleteOwned(stale))
        .isInstanceOf(DnaRuntimeException.class);
    for (var table : List.of("records", "details")) {
      assertThat(database.jdbc().queryForObject(
          "select count(*) from " + table + " where deleted_at is null", Long.class)).isEqualTo(2);
    }
    var current = new OwnedRecordDeleteTransaction(new RecordTransactionCommand("model-1", SCHEMA,
        List.of(firstDelete, new RecordDeleteMutation("second", second.recordId(), second.version())),
        30_000, "batch-current"), List.of(CHILD));
    var receipt = database.transactions().deleteOwned(current);
    assertThat(receipt.results()).hasSize(2);
    assertThat(database.transactions().deleteOwned(current).results()).isEqualTo(receipt.results());
    for (var table : List.of("records", "details")) {
      assertThat(database.jdbc().queryForObject(
          "select count(*) from " + table + " where deleted_at is null", Long.class)).isZero();
    }
  }

  @Test
  void failingChildRollsBackBusinessRowsAndRetainsTheRejection() throws Exception {
    var database = databaseFixture();
    var service = database.service();
    var bad =
        command(
            "rollback",
            new RecordCreateMutation("parent", Map.of("title", "Never committed")),
            List.of(
                new OwnedRecordRow(null, null, Map.of("title", "duplicate")),
                new OwnedRecordRow(null, null, Map.of("title", "duplicate"))));
    rejected(() -> service.execute(bad));
    rejected(() -> service.findResult(bad));
    rejected(() -> service.execute(bad));
    assertBusinessRollback(database);
    var good =
        command(
            "retry",
            new RecordCreateMutation("parent", Map.of("title", "Committed")),
            List.of(new OwnedRecordRow(null, null, Map.of("title", "unique"))));
    var created = service.execute(good);
    assertThat(service.get(query(created.recordId())).parent().values())
        .containsEntry("title", "Committed");
  }

  @Test
  void rejectsForeignRowsAndStaleParentWithoutChangingEitherAggregate() throws Exception {
    var service = fixture();
    var first =
        service.execute(
            command(
                "first",
                new RecordCreateMutation("parent", Map.of("title", "A")),
                List.of(new OwnedRecordRow(null, null, Map.of("title", "A row")))));
    var second =
        service.execute(
            command("second", new RecordCreateMutation("parent", Map.of("title", "B")), List.of()));
    var row = service.get(query(first.recordId())).details().get("child-1").getFirst();
    assertThatThrownBy(
            () ->
                service.execute(
                    command(
                        "foreign",
                        new RecordUpdateMutation(
                            "parent", second.recordId(), 0, Map.of("title", "Bad")),
                        List.of(
                            new OwnedRecordRow(
                                row.id(), row.version(), Map.of("title", "Stolen"))))))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    assertThat(service.get(query(first.recordId())).details().get("child-1").getFirst().values())
        .containsEntry("title", "A row");
    assertThat(service.get(query(second.recordId())).parent().version()).isZero();
    assertThatThrownBy(
            () ->
                service.execute(
                    command(
                        "stale",
                        new RecordUpdateMutation(
                            "parent", first.recordId(), 99, Map.of("title", "Bad")),
                        List.of())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void snapshotsFreezeExactCommittedValuesAcrossLaterUpdatesDeletesAndRetries() throws Exception {
    var service = fixture();
    var base =
        command(
            "snapshot-create",
            new RecordCreateMutation(
                "parent", Map.of("title", "提交時", "amount", "9007199254740993.01")),
            List.of(
                new OwnedRecordRow(
                    null, null, Map.of("title", "中文・日本語", "amount", "9007199254740993.02"))));
    var captured =
        new OwnedRecordCommand(
            base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    assertThat(service.snapshot(captured)).isNull();
    var created = service.execute(captured);
    var snapshot = service.snapshot(captured);
    assertThat(snapshot.parent().values())
        .containsEntry("title", "提交時")
        .containsEntry("amount", "9007199254740993.01");
    assertThat(snapshot.details().get("child-1").getFirst().values())
        .containsEntry("amount", "9007199254740993.02");
    var wire =
        new ObjectMapper()
            .readValue(new ObjectMapper().writeValueAsBytes(snapshot), OwnedRecordView.class);
    assertThat(wire).isEqualTo(snapshot);
    assertThat(snapshot.details().get("child-1").getFirst().values())
        .containsEntry("title", "中文・日本語");
    var patch =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                "model-1",
                SCHEMA,
                List.of(
                    new RecordUpdateMutation(
                        "parent", created.recordId(), 0, Map.of("title", "修改后"))),
                30_000,
                "capture-unchanged-children"),
            List.of(),
            new OwnedRecordSnapshotRequest(List.of(CHILD)));
    service.execute(patch);
    assertThat(service.snapshot(patch).details()).isEqualTo(snapshot.details());
    service.execute(
        command(
            "delete-snapshotted",
            new RecordDeleteMutation("parent", created.recordId(), 1),
            List.of()));
    assertThat(service.snapshot(captured)).isEqualTo(snapshot);
    assertThat(service.execute(captured)).isEqualTo(created);
    assertThat(service.snapshot(captured)).isEqualTo(snapshot);
  }

  @Test
  void copiedHistoryRedactsRevokedTargetValuesWhileRetainingUnrelatedSourceValues()
      throws Exception {
    var database = databaseFixture();
    var base =
        command(
            "protected-history",
            new RecordCreateMutation("parent", Map.of("title", "Sensitive copy", "amount", "1.23")),
            List.of());
    var command =
        new OwnedRecordCommand(
            base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    database.service().execute(command);
    var values = database.service().snapshot(command);
    var origin = RecordReferenceCopyStoreTest.origin();
    var titleOrigin =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin(
            origin.relationId(),
            origin.mappingId(),
            origin.mode(),
            "title",
            origin.mappingRevision(),
            origin.targetModelId(),
            origin.targetAppId(),
            origin.targetFormId(),
            origin.targetResourceCode(),
            origin.targetRecordId(),
            origin.targetVersion(),
            origin.targetFieldId(),
            origin.requirements());
    var captured =
        RecordStoredSnapshot.capture(values, null)
            .withCopyOrigins(
                List.of(
                    new RecordReferenceCopySnapshot(
                        null,
                        values.parent().id(),
                        values.parent().version(),
                        List.of(titleOrigin))));
    // Privileged test setup simulates a v3 capture from the new transactional backend. Public
    // form writes remain gated. This reader must apply the actual source and target policies.
    database
        .jdbc()
        .update(
            "UPDATE simpoint_dna_record_snapshots_v1 SET snapshot_json = ?",
            new ObjectMapper().writeValueAsString(captured));
    var visible = database.service().snapshot(command);
    assertThat(visible.parent().values())
        .containsEntry("amount", "1.23")
        .doesNotContainKey("title");
    assertThat(new ObjectMapper().writeValueAsString(visible))
        .doesNotContain("Sensitive copy", "requirements", "copyOrigins", "customers-page");
  }

  @Test
  void failedAggregateDoesNotLeaveBusinessSnapshots() throws Exception {
    var database = databaseFixture();
    var service = database.service();
    var base =
        command(
            "failed-snapshot",
            new RecordCreateMutation("parent", Map.of("title", "No snapshot")),
            List.of(
                new OwnedRecordRow(null, null, Map.of("title", "duplicate")),
                new OwnedRecordRow(null, null, Map.of("title", "duplicate"))));
    var captured =
        new OwnedRecordCommand(
            base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    rejected(() -> service.execute(captured));
    rejected(() -> service.snapshot(captured));
    rejected(() -> service.findResult(captured));
    assertBusinessRollback(database);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void copiedHistoryKeepsOldValueButRechecksCurrentTargetMaskAndDeletion(boolean withDerived)
      throws Exception {
    var database = databaseFixture();
    var target =
        database
            .service()
            .execute(
                command(
                    "copy-target",
                    new RecordCreateMutation("parent", Map.of("title", "Original source")),
                    List.of()));
    var base =
        command(
            "copy-source",
            new RecordCreateMutation(
                "parent", Map.of("title", "Original source", "amount", "1.23")),
            List.of());
    var command =
        new OwnedRecordCommand(
            base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    database.service().execute(command);
    var values = database.service().snapshot(command);
    var requirement =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement(
            "model-1",
            "app",
            "form",
            "target-page",
            target.recordId(),
            null,
            null,
            List.of("title"));
    var origin =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin(
            "relation",
            "mapping",
            org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.Mode
                .SERVER_SNAPSHOT,
            "title",
            "a".repeat(64),
            "model-1",
            "app",
            "form",
            "target-page",
            target.recordId(),
            0,
            "title",
            List.of(requirement));
    var captured =
        RecordStoredSnapshot.capture(values, null)
            .withCopyOrigins(
                List.of(
                    new RecordReferenceCopySnapshot(
                        null,
                        values.parent().id(),
                        0,
                        List.of(origin),
                        withDerived
                            ? List.of(
                                new RecordDerivedCopyOrigin(
                                    1,
                                    "amount",
                                    1,
                                    "a".repeat(64),
                                    List.of(
                                        new RecordAggregateReadRequirement(
                                            "model-1", values.parent().id(), List.of("title"))),
                                    List.of(requirement)))
                            : List.of())));
    database
        .jdbc()
        .update(
            "UPDATE simpoint_dna_record_snapshots_v1 SET snapshot_json = ?",
            new ObjectMapper().writeValueAsString(captured));
    var access = database.access();
    var targetAccess =
        new RecordAccessContext(
            access.actor(),
            "target-page",
            access.readableFields(),
            Set.of(),
            Set.of(),
            access.rowScope(),
            access.organizationId(),
            1);
    when(database.models().resolveCurrent(access.actor(), "model-1")).thenReturn(database.parent());
    when(database.policies().authorizeReference(access.actor(), database.parent(), "target-page"))
        .thenReturn(targetAccess);
    var authority = new org.simplepoint.core.AuthorizationContext();
    authority.setResourceDataScopes(
        Map.of("target-page", new org.simplepoint.core.ResourceDataScope("ALL", Set.of(), false)));
    authority.setResourceFieldPermissions(Map.of("target-page", Map.of()));
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authority);
    try {
      assertThat(database.service().snapshot(command).parent().values())
          .containsEntry("title", "Original source");
      database
          .service()
          .execute(
              command(
                  "change-target",
                  new RecordUpdateMutation(
                      "parent", target.recordId(), 0, Map.of("title", "Changed later")),
                  List.of()));
      assertThat(database.service().snapshot(command).parent().values())
          .containsEntry("title", "Original source");
      var masked =
          new RecordAccessContext(
              targetAccess.actor(),
              targetAccess.resourceCode(),
              targetAccess.readableFields(),
              Set.of(),
              Set.of("title"),
              targetAccess.rowScope(),
              null,
              1);
      when(database.policies().authorizeReference(access.actor(), database.parent(), "target-page"))
          .thenReturn(masked);
      assertThat(database.service().snapshot(command).parent().values()).doesNotContainKey("title");
      if (withDerived) {
        assertThat(database.service().snapshot(command).parent().values())
            .doesNotContainKey("amount");
      } else {
        assertThat(database.service().snapshot(command).parent().values())
            .containsEntry("amount", "1.23");
      }
      when(database.policies().authorizeReference(access.actor(), database.parent(), "target-page"))
          .thenReturn(targetAccess);
      database
          .service()
          .execute(
              command(
                  "delete-target",
                  new RecordDeleteMutation("parent", target.recordId(), 1),
                  List.of()));
      assertThat(database.service().snapshot(command).parent().values()).doesNotContainKey("title");
      if (withDerived) {
        assertThat(database.service().snapshot(command).parent().values())
            .doesNotContainKey("amount");
      } else {
        assertThat(database.service().snapshot(command).parent().values())
            .containsEntry("amount", "1.23");
      }
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(
          org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "true,true"})
  void currentPageLookupAndAggregateDetailApplyProofAfterTheSourceReadAndRejectInference(
      boolean withDerived, boolean calculate) throws Exception {
    var f = databaseFixture();
    var target =
        f.service()
            .execute(
                command(
                    "current-target",
                    new RecordCreateMutation("parent", Map.of("title", "Customer")),
                    List.of()));
    var source =
        f.service()
            .execute(
                command(
                    "current-copy",
                    new RecordCreateMutation(
                        "parent", Map.of("title", "Copied", "amount", "12.34")),
                    List.of()));
    var requirement =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement(
            "model-1",
            "app",
            "form",
            "target-page",
            target.recordId(),
            null,
            null,
            List.of("title"));
    var origin =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin(
            "relation",
            "mapping",
            org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.Mode
                .SERVER_SNAPSHOT,
            "title",
            "a".repeat(64),
            "model-1",
            "app",
            "form",
            "target-page",
            target.recordId(),
            0,
            "title",
            List.of(requirement));
    var key = new RecordReferenceCopyStore.Key("tenant-a", "model-1", source.recordId());
    var derived =
        withDerived
            ? List.of(
                new RecordDerivedCopyOrigin(
                    1,
                    "amount",
                    1,
                    "a".repeat(64),
                    List.of(
                        new RecordAggregateReadRequirement(
                            "model-1", source.recordId(), List.of("title"))),
                    List.of(requirement)))
            : List.<RecordDerivedCopyOrigin>of();
    var originJson =
        (String)
            new RecordReferenceCopyStore()
                .write(key, "fixture", null, 0, List.of(origin), derived, java.time.Instant.EPOCH)
                .parameters()
                .getLast();
    f.jdbc()
        .update(
            "INSERT INTO simpoint_dna_reference_copies_v1 (id, tenant_id, record_version,"
                + " created_by, created_at, updated_by, updated_at, model_id, source_record_id,"
                + " origins_json) VALUES (?, ?, 0, 'fixture', CURRENT_TIMESTAMP, 'fixture',"
                + " CURRENT_TIMESTAMP, ?, ?, ?)",
            key.id(),
            key.tenantId(),
            key.modelId(),
            key.recordId(),
            originJson);
    var policy =
        new RecordCalculationPolicy(
            1,
            "app",
            "form",
            calculate
                ? new RecordSchemaReference(
                    1,
                    new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(
                            new com.fasterxml.jackson.databind.ObjectMapper())
                        .encode(f.parent().requestedSchema())
                        .checksum())
                : SCHEMA,
            new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition(
                "1", calculate ? Map.of("amount", "LEN([title])") : Map.of(), Map.of()),
            Map.of("model-1", withDerived ? Set.of("title", "amount") : Set.of("title")));
    var parameters =
        new RecordCalculationStore()
            .insert(
                new RecordWriteFenceKey("tenant-a", "model-1"),
                1,
                "fixture",
                java.time.Instant.EPOCH,
                policy)
            .parameters();
    f.jdbc()
        .update(
            "INSERT INTO simpoint_dna_record_calculations_v1 (id, tenant_id, model_id,"
                + " policy_revision, definition_checksum, definition_json) VALUES (?, ?, ?, ?, ?,"
                + " ?)",
            parameters.get(0),
            parameters.get(1),
            parameters.get(7),
            parameters.get(8),
            parameters.get(9),
            parameters.get(10));
    f.jdbc().update("UPDATE simpoint_dna_record_write_fences_v1 SET write_revision = 1");
    var raw = f.access();
    when(f.models().resolve(raw.actor(), "model-1", policy.parentSchema())).thenReturn(f.parent());
    var targetAccess =
        new RecordAccessContext(
            raw.actor(),
            "target-page",
            raw.readableFields(),
            Set.of(),
            Set.of(),
            raw.rowScope(),
            null,
            1);
    when(f.models().resolveCurrent(raw.actor(), "model-1")).thenReturn(f.parent());
    when(f.policies().authorizeReference(raw.actor(), f.parent(), "target-page"))
        .thenReturn(targetAccess);
    var authority = new org.simplepoint.core.AuthorizationContext();
    authority.setResourceDataScopes(
        Map.of("target-page", new org.simplepoint.core.ResourceDataScope("ALL", Set.of(), false)));
    authority.setResourceFieldPermissions(Map.of("target-page", Map.of()));
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authority);
    try {
      var lookup =
          new RecordLookupQuery("model-1", SCHEMA, source.recordId(), List.of("title", "amount"));
      final var page =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery(
              "model-1", SCHEMA, 0, 500, List.of("title", "amount"), null, List.of());
      assertThat(f.queries().get(lookup).values()).containsEntry("title", "Copied");
      if (withDerived) {
        assertThat(
                f.queries()
                    .get(
                        new RecordLookupQuery(
                            "model-1", SCHEMA, source.recordId(), List.of("amount")))
                    .values())
            .containsExactlyEntriesOf(Map.of("amount", "12.34"));
      }
      assertThat(f.service().get(new OwnedRecordQuery(lookup, List.of(CHILD))).parent().values())
          .containsEntry("title", "Copied");
      assertThat(f.queries().page(page).total()).isEqualTo(2);
      var edit =
          new OwnedRecordCommand(
              new RecordTransactionCommand(
                  "model-1",
                  SCHEMA,
                  List.of(
                      new RecordUpdateMutation(
                          "parent", source.recordId(), 0, Map.of("title", "Edited title"))),
                  30_000,
                  "edit-with-origin",
                  1),
              List.of(new OwnedRecordSet(CHILD, List.of())));
      assertThat(f.service().execute(edit).version()).isEqualTo(1);
      assertThat(f.service().execute(edit).version()).isEqualTo(1);
      assertThat(f.queries().get(lookup).values()).containsEntry("title", "Edited title");
      var preview =
          new OwnedRecordCommand(
              new RecordTransactionCommand(
                  "model-1",
                  SCHEMA,
                  List.of(
                      new RecordUpdateMutation(
                          "parent", source.recordId(), 1, Map.of("title", "Preview"))),
                  30_000,
                  "preview-with-origin",
                  1),
              List.of());
      assertThat(f.service().preview(preview).values()).containsEntry("title", "Preview");
      if (calculate) {
        assertThat(f.queries().get(lookup).values()).containsEntry("amount", "12.00");
        assertThat(f.service().preview(preview).values()).containsEntry("amount", "7.00");
      }
      assertThat(
              f.jdbc()
                  .queryForObject(
                      "SELECT record_version FROM simpoint_dna_reference_copies_v1 WHERE id = ?",
                      Long.class,
                      key.id()))
          .isEqualTo(1);
      var mask =
          new RecordAccessContext(
              raw.actor(),
              "target-page",
              raw.readableFields(),
              Set.of(),
              Set.of("title"),
              raw.rowScope(),
              null,
              1);
      when(f.policies().authorizeReference(raw.actor(), f.parent(), "target-page"))
          .thenReturn(mask);
      var projected = f.queries().get(lookup).values();
      assertThat(projected).doesNotContainKey("title");
      var maskedPreview = f.service().preview(preview);
      assertThat(maskedPreview.values()).doesNotContainKey("title");
      if (withDerived) {
        assertThat(projected).doesNotContainKey("amount");
        assertThat(maskedPreview.values()).doesNotContainKey("amount");
      } else {
        assertThat(projected).containsEntry("amount", "12.34");
      }
      assertThat(f.service().get(new OwnedRecordQuery(lookup, List.of(CHILD))).parent().values())
          .doesNotContainKey("title");
      var hiddenPage = f.queries().page(page);
      assertThat(hiddenPage.records()).hasSize(2);
      assertThat(hiddenPage.total()).isEqualTo(2);
      assertThat(
              hiddenPage.records().stream()
                  .filter(row -> row.id().equals(source.recordId()))
                  .findFirst()
                  .orElseThrow()
                  .values())
          .doesNotContainKey("title");
      var filter =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordFilter(
              org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator.EQUALS,
              "title",
              List.of("Copied"),
              List.of());
      assertThatThrownBy(
              () ->
                  f.queries()
                      .count(
                          new org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery(
                              "model-1", SCHEMA, filter)))
          .isInstanceOf(DnaRuntimeException.class);
      f.jdbc().update("UPDATE simpoint_dna_reference_copies_v1 SET record_version = 2");
      assertThatThrownBy(() -> f.queries().get(lookup)).isInstanceOf(DnaRuntimeException.class);
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(
          org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private static OwnedRecordQuery query(String id) {
    return new OwnedRecordQuery(
        new RecordLookupQuery("model-1", SCHEMA, id, List.of("title")), List.of(CHILD));
  }

  private static OwnedRecordCommand command(
      String key, RecordMutation parent, List<OwnedRecordRow> rows) {
    return new OwnedRecordCommand(
        new RecordTransactionCommand("model-1", SCHEMA, List.of(parent), 30_000, key),
        List.of(new OwnedRecordSet(CHILD, rows)));
  }

  private static OwnedRecordService fixture() throws Exception {
    return databaseFixture().service();
  }

  private static void rejected(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
  }

  private static void assertBusinessRollback(DatabaseFixture database) {
    for (String table :
        List.of(
            "records",
            "details",
            "simpoint_dna_record_snapshots_v1",
            "simpoint_dna_reference_copies_v1")) {
      assertThat(database.jdbc().queryForObject("select count(*) from " + table, Long.class))
          .isZero();
    }
    assertThat(
            database
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_dna_record_operations_v1", Long.class))
        .isEqualTo(1);
  }

  private record DatabaseFixture(
      OwnedRecordService service,
      JdbcTemplate jdbc,
      RuntimeRecordModel parent,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordAccessContext access,
      RecordQueryServiceImpl queries,
      RecordTransactionServiceImpl transactions) {}

  private static DatabaseFixture databaseFixture() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:owned-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    SimpleDataSource dataSource = new SimpleDataSource(h2);
    String system =
        "id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, record_version BIGINT NOT"
            + " NULL, created_by VARCHAR(64) NOT NULL, created_org_dept_id VARCHAR(64), created_at"
            + " TIMESTAMP NOT NULL, updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL,"
            + " deleted_at TIMESTAMP, title VARCHAR(128) NOT NULL";
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE simpoint_dna_record_operations_v1 ("
              + system.replace(", title VARCHAR(128) NOT NULL", "")
              + ", model_id VARCHAR(64), operation_key VARCHAR(128), request_fingerprint"
              + " VARCHAR(64), result_json CLOB)");
      statement.execute(
          "CREATE TABLE simpoint_dna_record_snapshots_v1 ("
              + system.replace(", title VARCHAR(128) NOT NULL", "")
              + ", model_id VARCHAR(64), operation_key VARCHAR(128), request_fingerprint"
              + " VARCHAR(64), snapshot_json CLOB)");
      statement.execute("CREATE TABLE records (" + system + ", amount DECIMAL(24,2))");
      statement.execute(
          "CREATE TABLE simpoint_dna_reference_copies_v1 ("
              + system.replace(", title VARCHAR(128) NOT NULL", "")
              + ", model_id VARCHAR(64), source_record_id VARCHAR(36), origins_json CLOB)");
      statement.execute(
          "CREATE TABLE details ("
              + system
              + ", amount DECIMAL(24,2), parent_record_id VARCHAR(36) NOT NULL, row_order INT NOT"
              + " NULL, UNIQUE(parent_record_id, title))");
    }
    var title =
        new LogicalFieldDefinition(
            "title", "title", "Title", LogicalFieldType.STRING, false, 128, null, null, null);
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                title,
                new LogicalFieldDefinition(
                    "amount",
                    "amount",
                    "Amount",
                    LogicalFieldType.DECIMAL,
                    true,
                    null,
                    24,
                    2,
                    null)),
            List.of());
    var childSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                title,
                new LogicalFieldDefinition(
                    "amount",
                    "amount",
                    "Amount",
                    LogicalFieldType.DECIMAL,
                    true,
                    null,
                    24,
                    2,
                    null),
                new LogicalFieldDefinition(
                    "parent",
                    "parent_record_id",
                    "Parent",
                    LogicalFieldType.UUID,
                    false,
                    null,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "order",
                    "row_order",
                    "Order",
                    LogicalFieldType.INT32,
                    false,
                    null,
                    null,
                    null,
                    null)),
            List.of(),
            new OwnedModelRelation("model-1", "parent"));
    var parent =
        new RuntimeRecordModel(
            "model-1",
            "app",
            "form",
            1,
            schema,
            1,
            schema,
            new PhysicalSchemaTarget(
                "model-1", new RuntimeMetadataTarget("source", null, "PUBLIC", "records")));
    var child =
        new RuntimeRecordModel(
            "child-1",
            "app",
            "detail",
            1,
            childSchema,
            1,
            childSchema,
            new PhysicalSchemaTarget(
                "child-1", new RuntimeMetadataTarget("source", null, "PUBLIC", "details")));
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    var dialect = H2RecordWriteFenceFixture.prepare(dataSource, "tenant-a", "model-1");
    when(provider.open(any()))
        .thenReturn(
            new ManagedRuntimeDataSource(
                dataSource, false, null, null, dialect, null, null, "PUBLIC"));
    var context = mock(RuntimeRequestContext.class);
    var models = mock(RuntimeRecordModelResolver.class);
    var policies = mock(RuntimeRecordAccessPolicy.class);
    var actor = new RuntimeActor("tenant-a", "user-a");
    var access =
        new RecordAccessContext(
            actor,
            "app.form",
            Set.of("id", "record_version", "title", "amount", "parent_record_id", "row_order"),
            Set.of("title", "amount"),
            Set.of(),
            new DataScopeCondition(
                "ALL", "created_org_dept_id", "created_by", actor.actorId(), Set.of()),
            "dept");
    when(context.requireActor()).thenReturn(actor);
    when(models.resolve(actor, "model-1", SCHEMA)).thenReturn(parent);
    when(models.resolve(actor, "child-1", SCHEMA)).thenReturn(child);
    when(policies.authorize(actor, parent)).thenReturn(access);
    when(policies.owned(actor, child, access)).thenReturn(access);
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var backend = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
    var audit = mock(RecordAuditSupport.class);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var observer = mock(RecordExecutionObserver.class);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var schemas = new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(mapper);
    var calculation = new RecordCalculationWriteSupport(models, schemas, compiler);
    var fingerprints = new RecordRequestFingerprint(mapper);
    var service =
        new OwnedRecordService(
            context,
            models,
            policies,
            compiler,
            backend,
            fingerprints,
            admission,
            audit,
            observer,
            calculation,
            RecordCalculationTestSupport.access(models, policies, compiler),
            schemas);
    return new DatabaseFixture(
        service,
        new JdbcTemplate(h2),
        parent,
        models,
        policies,
        access,
        new RecordQueryServiceImpl(
            context,
            models,
            policies,
            compiler,
            backend,
            audit,
            admission,
            observer,
            RecordCalculationTestSupport.access(models, policies, compiler)),
        new RecordTransactionServiceImpl(context, models, policies, compiler, backend, audit,
            admission, observer, fingerprints, calculation));
  }
}
