package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;

class FormReferenceMappingProtocolTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void detailObservationCoordinatesAreOptionalAndUnambiguous() throws Exception {
    var runtime = new ApplicationFormRuntimeIdentity("form", 2);
    var legacy = new FormReferenceMappingQuery(runtime, null, null, null, List.of());
    assertThat(json.writeValueAsString(legacy)).doesNotContain("sourceRowId", "newDetailRow");
    String id = "11111111-1111-1111-1111-111111111111";
    var fresh = new FormReferenceMappingQuery(runtime, id, 1L, null, List.of(), null, true);
    assertThat(json.readValue(json.writeValueAsString(fresh), FormReferenceMappingQuery.class))
        .isEqualTo(fresh);
    assertThatThrownBy(
            () -> new FormReferenceMappingQuery(runtime, null, null, null, List.of(), id, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormReferenceMappingQuery(runtime, id, 1L, null, List.of(), id, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormReferenceMappingQuery(runtime, id, 1L, null, List.of(), null, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingTargetCannotSilentlyBecomeAnUnlinkAndSourceVersionsMustBePaired() throws Exception {
    String runtime = "\"runtime\":{\"formId\":\"form\",\"formVersion\":2}";
    assertThatThrownBy(() -> json.readValue("{" + runtime + "}", FormReferenceMappingQuery.class))
        .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    var clear =
        json.readValue(
            "{" + runtime + ",\"targetRecordId\":null}", FormReferenceMappingQuery.class);
    assertThat(clear.targetRecordId()).isNull();
    assertThatThrownBy(
            () -> new FormReferenceMappingQuery(clear.runtime(), null, 1L, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FormReferenceMappingQuery(clear.runtime(), null, null, null, List.of("a", "a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new FormReferenceMappingQuery(
                    new ApplicationFormRuntimeIdentity("form", 2),
                    null,
                    null,
                    null,
                    List.of("z", "a"))
                .applyDefaultMappingIds())
        .containsExactly("a", "z");
  }

  @Test
  void legacyCommandsKeepTheirWireShapeAndMappedCommandsRetainConfirmations() throws Exception {
    var runtime = new ApplicationFormRuntimeIdentity("form", 2);
    var mappings =
        new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent(
            "1",
            List.of(
                new org.simplepoint.plugin.app.form.api.model.relation
                    .FormReferenceMappingConfirmation(
                    "customer",
                    null,
                    null,
                    null,
                    new FormReferenceMappingObservation("a".repeat(64), null, null),
                    List.of())));
    var legacy =
        List.of(
            new org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand(
                runtime,
                "key",
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of()),
            new org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand(
                runtime,
                1,
                "key",
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of()),
            new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
                runtime,
                null,
                null,
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of()),
            new org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand(
                runtime,
                "key",
                null,
                null,
                "workflow",
                1,
                null,
                "Title",
                Map.of(),
                Map.of(),
                List.of()));
    var mapped =
        List.of(
            new org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand(
                runtime,
                "key",
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of(),
                mappings),
            new org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand(
                runtime,
                1,
                "key",
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of(),
                mappings),
            new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
                runtime,
                null,
                null,
                org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT,
                Map.of(),
                mappings),
            new org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand(
                runtime,
                "key",
                null,
                null,
                "workflow",
                1,
                null,
                "Title",
                Map.of(),
                Map.of(),
                List.of(),
                mappings));
    for (int i = 0; i < legacy.size(); i++) {
      var old = legacy.get(i);
      assertThat(json.writeValueAsString(old)).doesNotContain("referenceMappings");
      assertThat(json.readValue(json.writeValueAsString(old), old.getClass())).isEqualTo(old);
      var next = mapped.get(i);
      assertThat(json.writeValueAsString(next)).contains("referenceMappings");
      assertThat(json.readValue(json.writeValueAsString(next), next.getClass())).isEqualTo(next);
    }
  }

  @Test
  void exactCanonicalValuesRoundTripAndClearCannotCarryData() throws Exception {
    var observation =
        new FormReferenceMappingObservation(
            "a".repeat(64), "11111111-1111-1111-1111-111111111111", 4L);
    var view =
        new FormReferenceMappingView(
            observation,
            Map.of("amount", new FormValue(FormValueKind.DECIMAL, "9007199254740993.12")),
            Map.of("name", new FormValue(FormValueKind.STRING, "客户・日本")));
    assertThat(json.readValue(json.writeValueAsString(view), FormReferenceMappingView.class))
        .isEqualTo(view);
    assertThatThrownBy(
            () ->
                new FormReferenceMappingView(
                    new FormReferenceMappingObservation("a".repeat(64), null, null),
                    view.snapshots(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormReferenceMappingView(observation, view.snapshots(), view.snapshots()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
