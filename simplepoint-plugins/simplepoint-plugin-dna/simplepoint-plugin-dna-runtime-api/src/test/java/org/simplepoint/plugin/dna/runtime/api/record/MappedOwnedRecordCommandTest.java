package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MappedOwnedRecordCommandTest {
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private static final String REVISION = "a".repeat(64);
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, REVISION);

  @Test
  void confirmationsBindExactParentOrSubmittedDetailCoordinates() {
    var observation = new RecordReferenceMappingObservation(REVISION, RECORD, 4L);
    var parent =
        new RecordReferenceMappingConfirmation(
            "customer", null, null, null, observation, List.of());
    var child =
        new RecordReferenceMappingConfirmation(
            "product", "lines", 1, RECORD, observation, List.of("price"));
    var confirmations = new ArrayList<>(List.of(child, parent));
    var command = new MappedOwnedRecordCommand("1", aggregate(), null, confirmations);
    confirmations.clear();
    assertThat(command.confirmations()).containsExactly(parent, child);
    assertThatThrownBy(() -> command.confirmations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    for (var invalid :
        List.of(
            new RecordReferenceMappingConfirmation(
                "product", "lines", 0, RECORD, observation, List.of()),
            new RecordReferenceMappingConfirmation(
                "product", "lines", 1, null, observation, List.of()),
            new RecordReferenceMappingConfirmation(
                "product", "lines", 2, null, observation, List.of()),
            new RecordReferenceMappingConfirmation(
                "product", "absent", 0, null, observation, List.of()))) {
      assertThatThrownBy(
              () -> new MappedOwnedRecordCommand("1", aggregate(), null, List.of(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> new MappedOwnedRecordCommand("1", aggregate(), null, List.of(child, child)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultSelectionsAreExplicitCanonicalAndCannotSmuggleCopiedValues() throws Exception {
    var mapper = new ObjectMapper();
    var observation = new RecordReferenceMappingObservation(REVISION, RECORD, 4L);
    var confirmation =
        new RecordReferenceMappingConfirmation(
            "customer", null, null, null, observation, List.of("tax", "price"));
    var request = new MappedOwnedRecordCommand("1", aggregate(), null, List.of(confirmation));
    assertThat(request.confirmations().getFirst().applyDefaultMappingIds())
        .containsExactly("price", "tax");
    var json = mapper.writeValueAsString(request);
    assertThat(mapper.readValue(json, MappedOwnedRecordCommand.class)).isEqualTo(request);
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    mapper
                        .writeValueAsString(confirmation)
                        .replace(
                            "\"observation\":{",
                            "\"copiedValues\":{\"price\":1},\"observation\":{"),
                    RecordReferenceMappingConfirmation.class))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                new RecordReferenceMappingConfirmation(
                    "customer", null, null, null, observation, List.of("price", "price")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedTargetsVersionsAndRowCoordinatesAreRejectedBeforeRuntimeResolution() {
    for (String id : List.of("foreign", "1-1-1-1-1", " " + RECORD)) {
      assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, id, 1L))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, RECORD, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, null, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, RECORD, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation("old", RECORD, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    var cleared = new RecordReferenceMappingObservation(REVISION, null, null);
    assertThat(cleared.targetRecordId()).isNull();
    for (Integer index : new Integer[] {null, -1, 40}) {
      assertThatThrownBy(
              () ->
                  new RecordReferenceMappingConfirmation(
                      "product", "lines", index, null, cleared, List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new RecordReferenceMappingConfirmation(
                    "product", null, 0, null, cleared, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletionCannotCarryMappingEffectsAndInverseUnlinkRetainsItsUpdateConstraint() {
    var deletion =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                "model",
                SCHEMA,
                List.of(new RecordDeleteMutation("parent", RECORD, 1)),
                1000,
                "delete"),
            List.of());
    var clear =
        new RecordReferenceMappingConfirmation(
            "customer",
            null,
            null,
            null,
            new RecordReferenceMappingObservation(REVISION, null, null),
            List.of());
    assertThatThrownBy(() -> new MappedOwnedRecordCommand("1", deletion, null, List.of(clear)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new MappedOwnedRecordCommand("1", deletion, null, List.of()).confirmations())
        .isEmpty();
    var unlink =
        new RelatedRecordScope(
            "1", "customer", "customers", RECORD, RelatedRecordScope.Mode.UNLINK, null);
    assertThatThrownBy(() -> new MappedOwnedRecordCommand("1", aggregate(), unlink, List.of(clear)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MappedOwnedRecordCommand("2", aggregate(), null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OwnedRecordCommand aggregate() {
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            "model",
            SCHEMA,
            List.of(new RecordCreateMutation("parent", Map.of("name", "Trade"))),
            1000,
            "create"),
        List.of(
            new OwnedRecordSet(
                new OwnedRecordModel("lines", SCHEMA),
                List.of(
                    new OwnedRecordRow(null, null, Map.of("name", "New")),
                    new OwnedRecordRow(RECORD, 2L, Map.of("name", "Existing"))))));
  }
}
