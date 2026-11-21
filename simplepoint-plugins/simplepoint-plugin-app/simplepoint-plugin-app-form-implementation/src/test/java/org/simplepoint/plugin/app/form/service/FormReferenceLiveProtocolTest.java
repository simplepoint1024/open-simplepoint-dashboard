package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView;

class FormReferenceLiveProtocolTest {
  @Test
  void distinguishesClearFromMissingSelectionAndPreservesCanonicalValues() throws Exception {
    var mapper = new ObjectMapper();
    var query = new FormReferenceLiveQuery(new ApplicationFormRuntimeIdentity("form", 1), null);
    assertThat(mapper.readValue(mapper.writeValueAsBytes(query), FormReferenceLiveQuery.class))
        .isEqualTo(query);
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"runtime\":{\"formId\":\"form\",\"formVersion\":1}}",
                    FormReferenceLiveQuery.class))
        .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    var result =
        new FormReferenceLiveView(
            "11111111-1111-1111-1111-111111111111",
            1L,
            Map.of(
                "number",
                new FormValue(FormValueKind.INTEGER, "9007199254740993"),
                "name",
                new FormValue(FormValueKind.STRING, "客户・日本")));
    assertThat(mapper.readValue(mapper.writeValueAsBytes(result), FormReferenceLiveView.class))
        .isEqualTo(result);
  }

  @Test
  void unavailableOrClearedSelectionsCannotCarryStaleValues() {
    var value = Map.of("name", new FormValue(FormValueKind.STRING, "stale"));
    assertThatThrownBy(() -> new FormReferenceLiveView(null, null, value))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormReferenceLiveView("11111111-1111-1111-1111-111111111111", null, value))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FormReferenceLiveQuery(
                    new ApplicationFormRuntimeIdentity("form", 1), "invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchRejectsDuplicateNonCanonicalAndUnboundedIdentities() throws Exception {
    var runtime = new ApplicationFormRuntimeIdentity("form", 1);
    String id = "11111111-1111-1111-1111-111111111111";
    var query =
        new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
            runtime, java.util.List.of(id));
    var mapper = new ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsBytes(query), query.getClass()))
        .isEqualTo(query);
    assertThatThrownBy(
            () ->
                new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
                    runtime, java.util.List.of(id, id)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
                    runtime,
                    java.util.stream.IntStream.range(0, 101)
                        .mapToObj(i -> new java.util.UUID(1, i).toString())
                        .toList()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
