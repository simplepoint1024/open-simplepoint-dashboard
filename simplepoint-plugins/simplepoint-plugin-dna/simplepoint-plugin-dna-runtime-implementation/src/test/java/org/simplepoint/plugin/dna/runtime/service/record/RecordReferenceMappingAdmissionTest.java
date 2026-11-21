package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordReferenceMappingAdmissionTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private static final String TARGET = "22222222-2222-2222-2222-222222222222";
  private static final String OTHER = "33333333-3333-3333-3333-333333333333";
  private static final String REVISION = "a".repeat(64);

  @Test
  void validMappingCompilesButChangingOrClearingItsReferenceRequiresConfirmation() {
    var rules = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT).rules();
    var current = Map.<String, Object>of("customer_id", TARGET);
    for (var previous :
        List.of(Map.<String, Object>of(), Map.<String, Object>of("customer_id", OTHER))) {
      rejected(
          () -> rules.checks("parent", ROW, previous, current, current),
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    }
    rejected(
        () -> rules.checks("parent", ROW, null, current, current),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    rejected(
        () -> rules.checks("parent", ROW, current, Map.of(), Map.of()),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    assertThat(
            rules
                .checks("parent", ROW, current, current, current)
                .getFirst()
                .requireAuthorization())
        .isFalse();
    assertThat(rules.checks("parent", ROW, null, Map.of(), Map.of()).getFirst().targetRecordId())
        .isNull();
  }

  @Test
  void explicitRefreshCarriesExactVersionAndRevisionEvenWhenReferenceIsUnchanged() {
    var rules = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT).rules();
    var current = Map.<String, Object>of("customer_id", TARGET);
    var observation = new RecordReferenceMappingObservation(REVISION, TARGET, 7L);
    var check =
        rules
            .checks("parent", ROW, current, current, current, Map.of("customer", observation))
            .getFirst();
    assertThat(check.requireAuthorization()).isTrue();
    assertThat(check.expectedTargetVersion()).isEqualTo(7);
    assertThat(check.mappingRevision()).isEqualTo(REVISION);
    rejected(
        () ->
            rules.checks(
                "parent",
                ROW,
                null,
                current,
                current,
                Map.of("customer", new RecordReferenceMappingObservation(REVISION, OTHER, 7L))),
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    rejected(
        () -> rules.checks("parent", ROW, null, current, current, Map.of("unknown", observation)),
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE);
    var cleared =
        rules
            .checks(
                "parent",
                ROW,
                current,
                Map.of(),
                Map.of(),
                Map.of("customer", new RecordReferenceMappingObservation(REVISION, null, null)))
            .getFirst();
    assertThat(cleared.targetRecordId()).isNull();
    assertThat(cleared.requireAuthorization()).isFalse();
    assertThat(cleared.mappingRevision()).isEqualTo(REVISION);
  }

  @Test
  void directClientWritesCannotSetSnapshotDestinationsButEditableDefaultsRemainEditable() {
    var snapshot = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var editable = fixture(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    assertThat(snapshot.binding().outputs("source")).containsExactly("address");
    assertThat(editable.binding().outputs("source")).isEmpty();
    var actor = new RuntimeActor("tenant", "actor");
    var access =
        new RecordAccessContext(
            actor,
            "source-page",
            Set.of("customer_id", "address"),
            Set.of("customer_id", "address"),
            Set.of(),
            null,
            null);
    var mutation = new RecordCreateMutation("create", Map.of("address", "手工地址😀"));
    var compiler =
        new RecordSqlCompiler(
            new org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter());
    rejected(
        () ->
            compiler.validateIntent(
                snapshot.model(), access, mutation, snapshot.binding().outputs("source")),
        DnaRuntimeErrorCode.RECORD_ACCESS_DENIED);
    compiler.validateIntent(
        editable.model(), access, mutation, editable.binding().outputs("source"));
  }

  @Test
  void mappingPublicationUsesTheAuthoritativeDurableCoordinator() {
    var fixture = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    fixture.rules();
    var coordinator = mock(RecordPublicationCoordinator.class);
    var fingerprints = mock(RecordRequestFingerprint.class);
    var adapter = new RecordCalculationPublicationAdapter(coordinator, fingerprints);
    var command = mock(RecordWritePolicyPublicationCommand.class);
    when(command.calculation()).thenReturn(fixture.definition());
    var actor = new RuntimeActor("tenant", "actor");
    var stopped = new IllegalStateException("test coordinator sentinel");
    when(coordinator.advance(actor, command, false)).thenThrow(stopped);
    assertThatThrownBy(() -> adapter.publish(actor, command)).isSameAs(stopped);
    verify(coordinator).advance(actor, command, false);
    verifyNoInteractions(fingerprints);
  }

  @Test
  void publicationWithoutCalculationPreservesItsExistingCoordinatorPath() {
    var coordinator = mock(RecordPublicationCoordinator.class);
    var adapter =
        new RecordCalculationPublicationAdapter(coordinator, mock(RecordRequestFingerprint.class));
    var command = mock(RecordWritePolicyPublicationCommand.class);
    var actor = new RuntimeActor("tenant", "actor");
    var stopped = new IllegalStateException("test coordinator sentinel");
    when(coordinator.advance(actor, command, false)).thenThrow(stopped);
    assertThatThrownBy(() -> adapter.publish(actor, command)).isSameAs(stopped);
    verify(coordinator).advance(actor, command, false);
  }

  @Test
  void malformedMappingCannotBorrowUndeclaredFieldsOrOverwriteComputedOrReferenceFields() {
    var original = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    assertThatThrownBy(() -> original.withDestination("customer"))
        .isInstanceOf(IllegalArgumentException.class);
    rejected(
        original.withDestination("missing")::rules, DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE);
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1",
                    Map.of("address", "1"),
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    original.definition().referencesOrEmpty()))
        .isInstanceOf(IllegalArgumentException.class);
    var schema = original.model().activeSchema();
    var undeclared =
        new PhysicalSchemaDefinition(
            schema.contractVersion(),
            schema.fields(),
            List.of(),
            null,
            null,
            List.of(new LogicalReferenceDefinition("customer", "customer", "target", false)));
    var model =
        new RuntimeRecordModel(
            "source", "app", "source-form", 1, undeclared, original.model().target());
    rejected(
        () -> new Fixture(model, original.definition()).rules(),
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE);
  }

  private static void rejected(Runnable work, DnaRuntimeErrorCode expected) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(expected));
  }

  private static Fixture fixture(RecordReferenceMapping.Mode mode) {
    var reference =
        new LogicalFieldDefinition(
            "customer",
            "customer_id",
            "Customer",
            LogicalFieldType.UUID,
            true,
            null,
            null,
            null,
            null);
    var address =
        new LogicalFieldDefinition(
            "address", "address", "Address", LogicalFieldType.STRING, true, 100, null, null, null);
    var model =
        new RuntimeRecordModel(
            "source",
            "app",
            "source-form",
            1,
            new PhysicalSchemaDefinition(
                DnaRuntimeApiVersion.CURRENT,
                List.of(reference, address),
                List.of(),
                null,
                null,
                List.of(
                    new LogicalReferenceDefinition(
                        "customer",
                        "customer",
                        "target",
                        false,
                        List.of(new LogicalReferenceField("address", LogicalFieldType.STRING))))),
            new PhysicalSchemaTarget(
                "source", new RuntimeMetadataTarget("test", "catalog", "schema", "source")));
    var mapped =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "customer"),
            "target",
            "app",
            "target-form",
            "resource",
            null,
            null,
            null,
            List.of(
                new RecordReferenceMapping(
                    "address-copy",
                    "address",
                    "address",
                    mode,
                    RecordReferenceConversion.IDENTITY,
                    RecordReferenceMapping.NullPolicy.CLEAR)));
    return new Fixture(
        model,
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, List.of(mapped)));
  }

  private record Fixture(RuntimeRecordModel model, RecordCalculationDefinition definition) {
    RecordReferenceRules rules() {
      var fields =
          model.activeSchema().fields().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      LogicalFieldDefinition::fieldId, value -> value));
      return new RecordReferenceRules(model, Map.of(), definition, Map.of("parent", fields));
    }

    RecordCalculationWriteSupport.Binding binding() {
      var installed =
          new RecordCalculationStore.Installed(
              1,
              new RecordCalculationPolicy(
                  1, "app", "source-form", new RecordSchemaReference(1, REVISION), definition));
      return new RecordCalculationWriteSupport.Binding(
          installed, model, Map.of(), Map.of(), rules());
    }

    Fixture withDestination(String destination) {
      var original = definition.referencesOrEmpty().getFirst();
      var mapping = original.mappings().getFirst();
      var rule =
          new RecordReferenceRule(
              "1",
              "customer",
              original.source(),
              "target",
              "app",
              "target-form",
              "resource",
              null,
              null,
              null,
              List.of(
                  new RecordReferenceMapping(
                      "address-copy",
                      "address",
                      destination,
                      mapping.mode(),
                      mapping.conversion(),
                      mapping.nullPolicy())));
      return new Fixture(
          model,
          new RecordCalculationDefinition(
              "1", Map.of(), Map.of(), null, null, null, null, List.of(rule)));
    }
  }
}
