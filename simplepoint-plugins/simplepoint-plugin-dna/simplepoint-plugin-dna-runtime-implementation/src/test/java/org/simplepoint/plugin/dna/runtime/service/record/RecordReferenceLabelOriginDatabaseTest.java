package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Native immutable label evidence survives a later replacement of the target's own copy. */
class RecordReferenceLabelOriginDatabaseTest {
  @AfterEach
  void clear() {
    ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void removingOriginalCopiedSourceHidesHistoryEvenAfterTargetOriginWasCleared(boolean postgres)
      throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      var original = f.customer("original secret");
      var target = f.customer("original secret");
      installProtection(f);
      var model = f.base.customer;
      var required =
          new RecordReferenceReadRequirement(
              model.physicalModelId(),
              "app",
              "customers",
              "customers",
              original,
              null,
              null,
              List.of("status"));
      var origin =
          new RecordReferenceCopyOrigin(
              "retained-copy",
              "status-copy",
              RecordReferenceMapping.Mode.EDITABLE_DEFAULT,
              "status",
              "b".repeat(64),
              model.physicalModelId(),
              "app",
              "customers",
              "customers",
              original,
              0,
              "status",
              List.of(required));
      var key = new RecordReferenceCopyStore.Key("tenant-a", model.physicalModelId(), target);
      write(
          f,
          f.base.update(model, target, 0, Map.of("status", "original secret")),
          new RecordReferenceCopyMutation(key, null, 1, List.of(origin)));
      var command =
          new OwnedRecordCommand(
              f.command(
                  UUID.randomUUID().toString(),
                  List.of(new RecordCreateMutation("create", Map.of("customer_id", target)))),
              List.of(),
              new OwnedRecordSnapshotRequest(List.of()));
      f.ownedService.execute(command);
      var captured = f.ownedService.snapshot(command);
      assertThat(captured.referenceSnapshots())
          .singleElement()
          .satisfies(
              label -> {
                assertThat(label.label()).isEqualTo("original secret");
                assertThat(label.targetVersion()).isEqualTo(1);
              });
      // Editing an ordinary retained copy can replace its own origin. Historical consumers must
      // continue requiring the evidence they captured before this replacement.
      write(
          f,
          f.base.update(model, target, 1, Map.of("status", "public replacement")),
          new RecordReferenceCopyMutation(
              key, new RecordReferenceCopyStore.Stored(1, List.of(origin)), 2, List.of()));
      assertThat(f.ownedService.snapshot(command)).isEqualTo(captured);
      write(f, f.base.delete(model, original, 0), null);
      var revoked = f.ownedService.snapshot(command);
      assertThat(revoked.referenceSnapshots()).isEmpty();
      assertThat(revoked.parent()).isEqualTo(captured.parent());
      assertThat(f.base.value(model, target, "status")).isEqualTo("public replacement");
      assertThat(f.ownedService.execute(command).recordId()).isEqualTo(captured.parent().id());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void participantHistoryKeepsViewerPolicyAndRequiresExactOriginalReceipt(boolean postgres)
      throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      var target = f.customer("Submitter private label");
      var command =
          new OwnedRecordCommand(
              f.command(
                  UUID.randomUUID().toString(),
                  List.of(new RecordCreateMutation("create", Map.of("customer_id", target)))),
              List.of(),
              new OwnedRecordSnapshotRequest(List.of()));
      var saved = f.ownedService.execute(command);
      var query =
          new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery(
              "1",
              "user",
              "app",
              "trades",
              "trades",
              saved.recordId(),
              saved.version(),
              command,
              null,
              null);
      f.authorize("approver", true, "SELF", Map.of(), Map.of());
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.ownedService.snapshot(command))
          .isInstanceOf(org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException.class);
      assertThat(f.ownedService.snapshotTargets(query))
          .singleElement()
          .satisfies(
              entrance -> {
                assertThat(entrance.modelId()).isEqualTo(f.base.customer.physicalModelId());
                assertThat(entrance.resourceCode()).isEqualTo("customers");
              });
      var restricted = f.ownedService.snapshotForParticipant(query);
      assertThat(restricted.parent().id()).isEqualTo(saved.recordId());
      assertThat(restricted.referenceSnapshots()).isEmpty();
      f.authorize("approver", true, "ALL", Map.of(), Map.of());
      assertThat(f.ownedService.snapshotForParticipant(query).referenceSnapshots()).hasSize(1);
      assertThat(org.simplepoint.core.AuthorizationContextHolder.getContext().getUserId())
          .isEqualTo("approver");
      var wrong =
          new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery(
              "1",
              "user",
              "app",
              "trades",
              "trades",
              saved.recordId(),
              saved.version() + 1,
              command,
              null,
              null);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> f.ownedService.snapshotTargets(wrong))
          .isInstanceOf(org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException.class);
      f.authorize(
          "approver",
          true,
          "ALL",
          Map.of(),
          Map.of(
              org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key(
                      f.base.trade.physicalModelId())
                  + "#"
                  + org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.field(
                      "customer"),
              "HIDDEN"));
      assertThat(f.ownedService.snapshotTargets(query)).isEmpty();
      assertThat(f.ownedService.snapshotForParticipant(query).referenceSnapshots()).isEmpty();
    }
  }

  private static void write(
      RecordReferenceQualificationDatabaseTest.Fixture f,
      RecordSqlStatement statement,
      RecordReferenceCopyMutation copy) {
    f.base.backend.executePreparedOwned(
        f.base.customer,
        List.of(),
        new RecordWriteAdmission("tenant-a", 1),
        30000,
        null,
        (reader, at, policy) ->
            new RecordWritePreparation(
                List.of(statement),
                null,
                1,
                List.of(),
                null,
                copy == null ? List.of() : List.of(copy)));
  }

  private static void installProtection(RecordReferenceQualificationDatabaseTest.Fixture f) {
    var model = f.base.customer;
    var codec = new PhysicalSchemaCodec(new ObjectMapper());
    var schema = new RecordSchemaReference(1, codec.encode(model.activeSchema()).checksum());
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of());
    var policy =
        new RecordCalculationPolicy(
            1,
            "app",
            "customers",
            schema,
            definition,
            Map.of(model.physicalModelId(), Set.of("status")));
    var command =
        new RecordWritePolicyPublicationCommand(
            model.physicalModelId(),
            "app",
            "customers",
            schema,
            0,
            1,
            "retained-label-copy",
            "d".repeat(64),
            Map.of(),
            30000,
            Map.of(),
            definition);
    var key = new RecordWriteFenceKey("tenant-a", model.physicalModelId());
    var intent =
        new RecordPublicationIntent(
            1,
            key,
            command,
            "d".repeat(64),
            "0".repeat(64),
            "publisher",
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),
            Map.of(
                model.physicalModelId(),
                new RecordPublicationIntent.Binding(
                    schema, schema, model.target().metadataTarget(), 0)),
            null);
    f.base.backend.publication(
        model,
        List.of(),
        key,
        30000,
        session -> {
          session.write(
              new RecordCalculationStore().insert(key, 1, "publisher", Instant.now(), policy));
          session.activate(intent);
          return null;
        });
  }
}
