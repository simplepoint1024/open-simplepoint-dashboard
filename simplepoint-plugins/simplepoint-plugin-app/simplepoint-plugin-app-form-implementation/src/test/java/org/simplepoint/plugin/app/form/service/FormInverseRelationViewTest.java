package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormPresentation;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;

class FormInverseRelationViewTest {
  @Test
  void viewsRoundTripWithoutAnotherRelationshipOrChangingLegacyJson() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = new FormPresentation(List.of(), 140);
    assertThat(mapper.writeValueAsString(legacy))
        .isEqualTo("{\"listFieldOrder\":[],\"labelWidth\":140}");
    assertThat(
            mapper.readValue("{\"listFieldOrder\":[],\"labelWidth\":140}", FormPresentation.class))
        .isEqualTo(legacy);
    var original =
        new ApplicationFormDocument(
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new FormPresentation(List.of(), 140, List.of(view("transactions"))),
            List.of());
    var codec = new FormDocumentCodec(mapper);
    var decoded = codec.decode(codec.encode(original).json());
    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.relations()).isEmpty();
    assertThat(decoded.presentation().inverseViews().getFirst().source().accessResourceId())
        .isEqualTo("trade-page");
  }

  @Test
  void boundedViewsUseStableIdentitiesBeforeResolvedPublicationValidation() {
    assertThatThrownBy(() -> view("../relation")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormPresentation(List.of(), null, Collections.nCopies(21, view("view"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FormPresentation(List.of(), null, List.of(view("view"), view("view"))))
        .isInstanceOf(IllegalArgumentException.class);
    var doc =
        new ApplicationFormDocument(
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new FormPresentation(List.of(), null, List.of(view("view"))),
            List.of());
    FormRelationPublicationValidator.validate(doc);
  }

  private static FormInverseRelationView view(String id) {
    return new FormInverseRelationView(
        id,
        "客户交易・日本語😀",
        new FormRelationTarget("trade-app", "trade", "trade-page"),
        "customer",
        true,
        true,
        false,
        false,
        null);
  }
}
