package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.*;

class FormAggregateValidationTest {
  @Test
  void exactDetailSumsCountsAndBoundsAreChecked() {
    var document = document(List.of(rule(FormValidationConstraint.Kind.DETAIL_SUM, "amount", null, null),
        rule(FormValidationConstraint.Kind.DETAIL_COUNT, null, "1", "40"),
        rule(FormValidationConstraint.Kind.DETAIL_RANGE, null, "0.01", null)));
    FormAggregateValidation.validateDefinition(document);
    var rows = Map.of("lines", List.<Map<String, Object>>of(
        Map.of("amount", "9007199254740993.01"), Map.of("amount", "0.09")));
    FormAggregateValidation.validate(document, Map.of("amount", "9007199254740993.10"), rows);
    assertThatThrownBy(() -> FormAggregateValidation.validate(document,
        Map.of("amount", "9007199254740993.11"), rows)).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormAggregateValidation.validate(document,
        Map.of("amount", "0"), Map.of("lines", List.of()))).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormAggregateValidation.validate(document,
        Map.of("amount", "-1"), Map.of("lines", List.of(Map.of("amount", "-1")))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void rejectsUnknownReferencesAndUnboundedOrExecutableDefinitions() {
    assertThatThrownBy(() -> FormAggregateValidation.validateDefinition(document(List.of(
        rule(FormValidationConstraint.Kind.DETAIL_SUM, "unknown", null, null)))))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormAggregateValidation.validateDefinition(document(List.of(
        rule(FormValidationConstraint.Kind.DETAIL_COUNT, null, "1", "41")))))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormAggregateValidation.validateDefinition(document(List.of(
        rule(FormValidationConstraint.Kind.RANGE, "amount", "${unsafe}", null)))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void canonicalChecksumsIncludeConstraintsButLegacyDocumentsKeepTheirShape() {
    var codec = new FormDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var legacy = document(List.of());
    var constrained = document(List.of(rule(FormValidationConstraint.Kind.RANGE, "amount", "0.01", "5000")));
    assertThat(codec.encode(legacy).json()).doesNotContain("constraints");
    assertThat(codec.encode(constrained).checksum()).isNotEqualTo(codec.encode(legacy).checksum());
    assertThat(codec.decode(codec.encode(constrained).json())).isEqualTo(constrained);
  }

  @Test
  void attachmentsAreBoundedStructuredReferencesAndCannotInjectDownloadUrls() {
    assertThat(FormAttachmentValues.parse("[{\"id\":\"object-1\",\"name\":\"发票.pdf\",\"size\":128}]")).hasSize(1);
    assertThatThrownBy(() -> FormAttachmentValues.parse("https://example.test/file"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormAttachmentValues.parse("[{\"id\":\"../other\",\"name\":\"发票.pdf\",\"size\":128}]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormAttachmentValues.parse("[{\"id\":\"object-1\",\"name\":\"发票.pdf\",\"size\":0}]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FormValidationConstraint rule(FormValidationConstraint.Kind kind, String field, String min, String max) {
    return new FormValidationConstraint(kind.name(), kind, field, null, "lines", "amount", min, max, "业务校验失败");
  }

  private static ApplicationFormDocument document(List<FormValidationConstraint> constraints) {
    var field = new FormFieldDefinition("amount", "amount", "金额", "金额", FormFieldType.DECIMAL,
        FormStorageMode.STORED, true, false, null, 24, 2, null, List.of());
    return new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of(),
        List.of(new FormDetailDefinition("lines", "lines", "费用明细", List.of(field), List.of())), constraints);
  }
}
