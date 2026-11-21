package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.simplepoint.plugin.dna.runtime.service.record.RecordReferenceQualificationDatabaseTest.Fixture;

class RelatedRecordWriteDatabaseTest {
  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void preservesScopeAndRecoversDeletedReceiptsWithoutReapplyingCandidateEligibility(
      boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      String customer = fixture.customer("ACTIVE");
      final String other = fixture.customer("ACTIVE");
      var create =
          request(
              fixture,
              "related-create",
              customer,
              new RecordCreateMutation(
                  "create", Map.of("customer_id", customer, "status", "DRAFT")));
      var saved = fixture.ownedService.executeRelated(create);
      assertThat(fixture.ownedService.executeRelated(create)).isEqualTo(saved);
      fixture.base.save(
          fixture.base.customer,
          fixture.base.update(fixture.base.customer, customer, 0, Map.of("status", "INACTIVE")));
      var update =
          request(
              fixture,
              "related-update",
              customer,
              new RecordUpdateMutation("update", saved.recordId(), 0, Map.of("note", "retained")));
      assertThat(fixture.ownedService.executeRelated(update).version()).isEqualTo(1);
      var wrong =
          request(
              fixture,
              "wrong-origin",
              other,
              new RecordUpdateMutation(
                  "update", saved.recordId(), 1, Map.of("note", "must roll back")));
      code(
          () -> fixture.ownedService.executeRelated(wrong),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "note"))
          .isEqualTo("retained");
      code(
          () -> fixture.ownedService.findRelatedResult(wrong),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      var delete =
          request(
              fixture,
              "related-delete",
              customer,
              new RecordDeleteMutation("delete", saved.recordId(), 1));
      var removed = fixture.ownedService.executeRelated(delete);
      assertThat(fixture.ownedService.executeRelated(delete)).isEqualTo(removed);
      assertThat(fixture.ownedService.findRelatedResult(delete)).isEqualTo(removed);
      code(
          () ->
              fixture.ownedService.executeRelated(
                  request(
                      fixture,
                      "related-delete",
                      other,
                      new RecordDeleteMutation("delete", saved.recordId(), 1))),
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
      code(
          () -> fixture.ownedService.execute(delete.command()),
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void refusesScopeChangingCreatesAndUpdatesAndSeparatesUnlinkFromRebind(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      String customer = fixture.customer("ACTIVE");
      final String other = fixture.customer("ACTIVE");
      var wrong =
          request(
              fixture,
              "wrong-create",
              customer,
              new RecordCreateMutation("create", Map.of("customer_id", other, "status", "DRAFT")));
      code(
          () -> fixture.ownedService.executeRelated(wrong),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      code(
          () -> fixture.ownedService.findRelatedResult(wrong),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      var saved =
          fixture.ownedService.executeRelated(
              request(
                  fixture,
                  "create",
                  customer,
                  new RecordCreateMutation(
                      "create", Map.of("customer_id", customer, "status", "DRAFT"))));
      code(
          () ->
              fixture.ownedService.executeRelated(
                  request(
                      fixture,
                      "rebind",
                      customer,
                      new RecordUpdateMutation(
                          "update", saved.recordId(), 0, Map.of("customer_id", other)))),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      Map<String, Object> cleared = new LinkedHashMap<>();
      cleared.put("customer_id", null);
      var intent =
          request(
              fixture,
              "unlink",
              customer,
              new RecordUpdateMutation("update", saved.recordId(), 0, cleared));
      var unlink =
          new RelatedRecordCommand(
              intent.command(),
              new RelatedRecordScope(
                  "1",
                  "trade-customer",
                  fixture.base.customer.physicalModelId(),
                  customer,
                  RelatedRecordScope.Mode.UNLINK,
                  null));
      var extra = new LinkedHashMap<String, Object>(cleared);
      extra.put("note", "must not change through unlink");
      var mixed =
          new RelatedRecordCommand(
              new OwnedRecordCommand(
                  fixture.command(
                      "unlink-with-extra-edit",
                      List.of(new RecordUpdateMutation("update", saved.recordId(), 0, extra))),
                  List.of()),
              unlink.scope());
      code(
          () -> fixture.ownedService.executeRelated(mixed),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "note")).isNull();
      assertThat(fixture.ownedService.executeRelated(unlink).version()).isEqualTo(1);
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "customer_id")).isNull();
      assertThat(fixture.ownedService.findRelatedResult(unlink).version()).isEqualTo(1);
      code(
          () ->
              fixture.ownedService.executeRelated(
                  request(
                      fixture,
                      "after-unlink",
                      customer,
                      new RecordUpdateMutation(
                          "update", saved.recordId(), 1, Map.of("note", "not a member")))),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void currentTargetAndSourceFieldAuthorizationRemainRequiredForUnchangedReferences(
      boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      String customer = fixture.customer("ACTIVE");
      var saved =
          fixture.ownedService.executeRelated(
              request(
                  fixture,
                  "create",
                  customer,
                  new RecordCreateMutation(
                      "create", Map.of("customer_id", customer, "status", "DRAFT"))));
      fixture.authorize(false, "ALL", Map.of());
      var denied =
          request(
              fixture,
              "revoked-target",
              customer,
              new RecordUpdateMutation("update", saved.recordId(), 0, Map.of("note", "denied")));
      code(
          () -> fixture.ownedService.executeRelated(denied),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      fixture.authorize(true, "ALL", Map.of());
      // Restoration cannot turn the exact rejected intent into a delayed write.
      code(
          () -> fixture.ownedService.executeRelated(denied),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
      for (String access : List.of("HIDDEN", "MASKED")) {
        var previous = AuthorizationContextHolder.getContext();
        var authorization = new org.simplepoint.core.AuthorizationContext();
        authorization.setUserId(previous.getUserId());
        authorization.setAttributes(previous.getAttributes());
        authorization.setResources(previous.getResources());
        authorization.setDataScopeType(previous.getDataScopeType());
        authorization.setResourceDataScopes(previous.getResourceDataScopes());
        authorization.setResourceFieldPermissions(
            Map.of(
                "customers",
                Map.of(),
                "trades",
                Map.of(
                    RecordPermissionModel.key(fixture.base.trade.physicalModelId())
                        + "#"
                        + RecordPermissionModel.field("customer"),
                    access)));
        RequestContextHolder.setContext(
            RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
        assertThat(authorization.fieldPermissionsFor("trades")).containsValue(access);
        code(
            () ->
                fixture.ownedService.executeRelated(
                    request(
                        fixture,
                        "field-" + access,
                        customer,
                        new RecordDeleteMutation("delete", saved.recordId(), 0))),
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void relatedSnapshotsRetainExactIntentAndImmutableValuesAfterLaterWrites(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      String customer = fixture.customer("ACTIVE");
      final String other = fixture.customer("ACTIVE");
      var draft =
          request(
              fixture,
              "related-snapshot",
              customer,
              new RecordCreateMutation(
                  "create",
                  Map.of("customer_id", customer, "status", "DRAFT", "note", "captured")));
      var command =
          new RelatedRecordCommand(
              new OwnedRecordCommand(
                  draft.command().parent(),
                  List.of(),
                  new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest(
                      List.of())),
              draft.scope());
      assertThat(fixture.ownedService.snapshotRelated(command)).isNull();
      var saved = fixture.ownedService.executeRelated(command);
      var captured = fixture.ownedService.snapshotRelated(command);
      assertThat(captured.parent().id()).isEqualTo(saved.recordId());
      assertThat(captured.parent().values()).containsEntry("note", "captured");
      fixture.ownedService.execute(
          request(
                  fixture,
                  "later-rebind",
                  customer,
                  new RecordUpdateMutation(
                      "update", saved.recordId(), 0, Map.of("customer_id", other, "note", "later")))
              .command());
      assertThat(fixture.ownedService.snapshotRelated(command)).isEqualTo(captured);
      assertThat(fixture.ownedService.executeRelated(command)).isEqualTo(saved);
      code(
          () -> fixture.ownedService.snapshot(command.command()),
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
      var changed =
          new RelatedRecordCommand(
              command.command(),
              new RelatedRecordScope(
                  "1",
                  "trade-customer",
                  fixture.base.customer.physicalModelId(),
                  other,
                  RelatedRecordScope.Mode.MAINTAIN,
                  null));
      code(
          () -> fixture.ownedService.snapshotRelated(changed),
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
    }
  }

  private static RelatedRecordCommand request(
      Fixture fixture, String key, String customer, RecordMutation mutation) {
    return new RelatedRecordCommand(
        new OwnedRecordCommand(fixture.command(key, List.of(mutation)), List.of()),
        new RelatedRecordScope(
            "1",
            "trade-customer",
            fixture.base.customer.physicalModelId(),
            customer,
            RelatedRecordScope.Mode.MAINTAIN,
            null));
  }

  private static void code(Runnable operation, DnaRuntimeErrorCode expected) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(expected));
  }
}
