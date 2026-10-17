package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

class FormPresentationAndAccessTest {
  private FormFieldDefinition field(FormFieldAccess access) {
    return new FormFieldDefinition("field", "name", "Name", "Name", FormFieldType.STRING, FormStorageMode.STORED,
        false, false, 100, null, null, null, List.of(), null, null, access);
  }
  private ApplicationFormDocument document(FormFieldAccess access, FormPresentation presentation) {
    var input = new FormComponentDefinition("input", "field.text", "1", "field", 24, null, null, null, null, false, Map.of(), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24, null, null, null, null, false, Map.of(), List.of(input));
    return new ApplicationFormDocument("1", List.of(field(access)), List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), List.of(), List.of(), List.of(), presentation);
  }
  @Test void operationPoliciesRejectDirectWritesAndPreserveReadValues() {
    var compiler = new FormRecordPayloadCompiler();
    for (var access : List.of(new FormFieldAccess(true, null, null), new FormFieldAccess(false, FormFieldAccess.CreateMode.HIDDEN, FormFieldAccess.EditMode.HIDDEN))) {
      for (var state : Arrays.asList(null, FormRecordState.DRAFT, FormRecordState.SUBMITTED)) {
        assertThatThrownBy(() -> compiler.compile(document(access, null), Map.of("field", "old"), Map.of("field", "forged"), state, FormRecordState.SUBMITTED)).isInstanceOf(ApplicationFormException.class);
        assertThat(compiler.validatedValues(document(access, null), Map.of("field", "old"), Map.of(), state, FormRecordState.SUBMITTED)).containsEntry("field", "old");
      }
    }
    var doc = document(new FormFieldAccess(false, null, FormFieldAccess.EditMode.READ), null);
    assertThat(compiler.compile(doc, Map.of(), Map.of("field", "new"), null, FormRecordState.DRAFT)).containsEntry("name", "new");
    assertThatThrownBy(() -> compiler.compile(doc, Map.of(), Map.of("field", "change"), FormRecordState.DRAFT, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
  }
  @Test void requiredFieldsThatLockOnEditMustBeCompleteBeforeFirstDraftAndPublication() {
    var original = document(new FormFieldAccess(false, null, FormFieldAccess.EditMode.READ), null);
    var required = new FormFieldDefinition("field", "name", "Name", "Name", FormFieldType.STRING, FormStorageMode.STORED,
        true, false, 100, null, null, null, List.of(), null, null, original.fields().getFirst().access());
    var locked = new ApplicationFormDocument("1", List.of(required), List.of(), original.scenes(), List.of());
    var compiler = new FormRecordPayloadCompiler();
    assertThatThrownBy(() -> compiler.compile(locked, Map.of(), Map.of(), null, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    assertThat(compiler.compile(locked, Map.of(), Map.of("field", "first"), null, FormRecordState.DRAFT)).containsEntry("name", "first");
    var requirement = FormSubmissionRequirements.compile(locked).getFirst().forState(FormRecordState.DRAFT);
    assertThat(requirement.missing(Map.of())).isTrue();
    assertThat(requirement.missing(Map.of("field", "first"))).isFalse();
  }

  @Test void activatedPoliciesFenceOlderPagesWhileKeepingTheirLayoutDefaults() {
    var old = document(null, new FormPresentation(List.of("field"), 100));
    var current = document(new FormFieldAccess(true, null, null), new FormPresentation(List.of(), 200));
    var projected = FormWritePolicyProjection.apply(old, current);
    assertThat(projected.fields().getFirst().access().allows(null)).isFalse();
    assertThat(projected.presentation()).isEqualTo(old.presentation());
    assertThat(FormWritePolicyProjection.apply(current, old).fields().getFirst().access().readOnly()).isTrue();
  }
  @Test void optionalContractPreservesLegacyBytesAndRoundTripsConfiguredPresentation() {
    var codec = new FormDocumentCodec(new ObjectMapper());
    var legacy = codec.encode(document(null, null));
    assertThat(legacy.json()).doesNotContain("presentation", "access");
    assertThat(codec.encode(codec.decode(legacy.json())).checksum()).isEqualTo(legacy.checksum());
    var configured = document(new FormFieldAccess(false, null, FormFieldAccess.EditMode.HIDDEN), new FormPresentation(List.of("field"), 120));
    assertThat(codec.decode(codec.encode(configured).json())).isEqualTo(configured);
    assertThat(codec.encode(configured).checksum()).isNotEqualTo(legacy.checksum());
  }
  @Test void listDefaultsRejectUnknownDuplicateIdentitiesAndInvalidWidths() {
    var validator = new FormDocumentValidator(new DefaultFormComponentRegistry());
    for (var invalid : List.of(new FormPresentation(List.of("missing"), 100), new FormPresentation(List.of("field", "field"), 100), new FormPresentation(List.of(), 0))) {
      assertThatThrownBy(() -> validator.validate(document(null, invalid))).isInstanceOf(ApplicationFormException.class);
    }
    validator.validate(document(null, new FormPresentation(List.of("field"), 120)));
  }
}
