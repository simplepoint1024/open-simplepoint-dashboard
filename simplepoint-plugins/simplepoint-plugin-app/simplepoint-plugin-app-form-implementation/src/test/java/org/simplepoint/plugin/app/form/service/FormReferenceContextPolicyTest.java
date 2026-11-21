package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter.Source;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.springframework.security.access.AccessDeniedException;

class FormReferenceContextPolicyTest {
  private static final String ORGANIZATION = "00000000-0000-0000-0000-000000000001";

  @Test
  void contextIntersectsStaticFilterAndKeepsParentRowAndTrustedOrganizationSeparate() {
    var relation =
        relation(
            true,
            List.of(
                filter("region", Source.FORM, "region"),
                filter("rowRegion", Source.ROW, "region"),
                filter("organization", Source.ORGANIZATION, null)));
    var source = document(relation);
    var authorized = new ArrayList<String>();
    var result =
        FormReferenceContextPolicy.resolve(
            source,
            relation,
            targets(),
            new FormReferenceSearchContext(
                Map.of("region", text("华北")), Map.of("region", text("東京"))),
            ORGANIZATION,
            (scope, field) -> authorized.add(scope + ":" + field.fieldId()));
    assertThat(result.ready()).isTrue();
    assertThat(result.condition().operator()).isEqualTo(RecordValueConditionOperator.AND);
    assertThat(result.condition().children())
        .extracting(condition -> condition.field())
        .containsExactly(
            "stored_region", "stored_row_region", "stored_organization", "stored_name");
    assertThat(result.condition().children())
        .extracting(condition -> condition.values().getFirst().canonicalValue())
        .containsExactly("华北", "東京", ORGANIZATION, "enabled");
    assertThat(authorized).containsExactly("FORM:region", "ROW:region");
  }

  @Test
  void missingInputCannotTurnIntoAnUnfilteredOrNullComparisonQuery() {
    var relation = relation(true, List.of(filter("region", Source.ROW, "region")));
    for (var value :
        List.of(
            new FormReferenceSearchContext(null, null),
            new FormReferenceSearchContext(
                null, Map.of("region", new FormValue(FormValueKind.NULL, null))),
            new FormReferenceSearchContext(null, Map.of("region", text(""))))) {
      var result =
          FormReferenceContextPolicy.resolve(
              document(relation), relation, targets(), value, ORGANIZATION, (scope, field) -> {});
      assertThat(result.ready()).isFalse();
      assertThat(result.condition()).isNull();
    }
  }

  @Test
  void undeclaredInputsAndForgedOrganizationAreRejected() {
    var relation = relation(false, List.of(filter("organization", Source.ORGANIZATION, null)));
    assertThatThrownBy(
            () ->
                FormReferenceContextPolicy.resolve(
                    document(relation),
                    relation,
                    targets(),
                    new FormReferenceSearchContext(
                        Map.of("organization", text(ORGANIZATION)), null),
                    ORGANIZATION,
                    (scope, field) -> {}))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(
            FormReferenceContextPolicy.resolve(
                    document(relation), relation, targets(), null, null, (scope, field) -> {})
                .ready())
        .isFalse();
    assertThatThrownBy(() -> filter("organization", Source.ORGANIZATION, "organization"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hiddenSourcesAreCheckedEvenWhenTheirInputIsAbsent() {
    var relation = relation(false, List.of(filter("region", Source.FORM, "region")));
    assertThatThrownBy(
            () ->
                FormReferenceContextPolicy.resolve(
                    document(relation),
                    relation,
                    targets(),
                    null,
                    ORGANIZATION,
                    (scope, field) -> {
                      throw new AccessDeniedException("hidden");
                    }))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void unrelatedRowsWrongTypesAndUnsupportedComparisonsCannotPublish() {
    var root = relation(false, List.of(filter("region", Source.ROW, "region")));
    assertThatThrownBy(() -> FormReferenceContextPolicy.validateSources(document(root), root))
        .isInstanceOf(ApplicationFormException.class);
    var incompatible = relation(false, List.of(filter("organization", Source.FORM, "region")));
    assertThatThrownBy(
            () ->
                FormReferenceContextPolicy.validate(
                    document(incompatible), incompatible, targets()))
        .isInstanceOf(ApplicationFormException.class);
    var orderedText =
        relation(
            false,
            List.of(
                new FormReferenceContextFilter(
                    "region", FormConditionOperator.GREATER_THAN, Source.FORM, "region")));
    assertThatThrownBy(
            () ->
                FormReferenceContextPolicy.validate(document(orderedText), orderedText, targets()))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void inputsAreTypedAndExactInt64ValuesDoNotPassThroughFloatingPoint() {
    var relation = relation(false, List.of(filter("number", Source.FORM, "number")));
    var input = new FormValue(FormValueKind.INTEGER, "9007199254740993");
    var result =
        FormReferenceContextPolicy.resolve(
            document(relation),
            relation,
            targets(),
            new FormReferenceSearchContext(Map.of("number", input), null),
            null,
            (scope, field) -> {});
    assertThat(result.condition().children().getFirst().values().getFirst().canonicalValue())
        .isEqualTo("9007199254740993");
    assertThatThrownBy(
            () ->
                FormReferenceContextPolicy.resolve(
                    document(relation),
                    relation,
                    targets(),
                    new FormReferenceSearchContext(Map.of("number", text(input.value())), null),
                    null,
                    (scope, field) -> {}))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void canonicalDocumentRetainsContextAndLegacyViewsDoNotGainOptionalMetadata() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = new FormReferenceView("name", List.of(), List.of(), null, false);
    assertThat(mapper.writeValueAsString(legacy)).doesNotContain("contextFilters");
    var relation = relation(false, List.of(filter("region", Source.FORM, "region")));
    var codec = new FormDocumentCodec(mapper);
    var encoded = codec.encode(document(relation));
    assertThat(codec.decode(encoded.json()).relations().getFirst().referenceView().contextFilters())
        .containsExactlyElementsOf(relation.referenceView().contextFilters());
    assertThat(FormRelationDependencies.freeze(relation, targetDocument()))
        .extracting(field -> field.fieldId())
        .containsExactly("name", "region");
  }

  private static FormReferenceContextFilter filter(String target, Source source, String field) {
    return new FormReferenceContextFilter(target, FormConditionOperator.EQUALS, source, field);
  }

  private static FormRelationDefinition relation(
      boolean detail, List<FormReferenceContextFilter> filters) {
    var fixed =
        new FormCondition(
            FormConditionOperator.EQUALS, "name", List.of(text("enabled")), List.of());
    return new FormRelationDefinition(
        "1",
        "customer",
        FormRelationCardinality.MANY_TO_ONE,
        new FormRelationField(detail ? "lines" : null, "customer"),
        new FormRelationTarget("app", "form", "resource"),
        null,
        new FormReferenceView("name", List.of(), List.of(), fixed, false, filters));
  }

  private static ApplicationFormDocument document(FormRelationDefinition relation) {
    return new ApplicationFormDocument(
        "1",
        List.of(
            field("region", "region", FormFieldType.STRING),
            field("number", "number", FormFieldType.INT64)),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new FormDetailDefinition(
                "lines",
                "lines",
                "明细",
                List.of(field("region", "region", FormFieldType.STRING)),
                List.of())),
        List.of(),
        null,
        List.of(relation));
  }

  private static ApplicationFormDocument targetDocument() {
    return new ApplicationFormDocument(
        "1", List.copyOf(targets().values()), List.of(), List.of(), List.of());
  }

  private static Map<String, FormFieldDefinition> targets() {
    return Map.of(
        "name",
        field("name", "stored_name", FormFieldType.STRING),
        "region",
        field("region", "stored_region", FormFieldType.STRING),
        "rowRegion",
        field("rowRegion", "stored_row_region", FormFieldType.STRING),
        "organization",
        field("organization", "stored_organization", FormFieldType.UUID),
        "number",
        field("number", "stored_number", FormFieldType.INT64));
  }

  private static FormFieldDefinition field(String id, String code, FormFieldType type) {
    return new FormFieldDefinition(
        id,
        code,
        id,
        id,
        type,
        FormStorageMode.STORED,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static FormValue text(String value) {
    return new FormValue(FormValueKind.STRING, value);
  }
}
