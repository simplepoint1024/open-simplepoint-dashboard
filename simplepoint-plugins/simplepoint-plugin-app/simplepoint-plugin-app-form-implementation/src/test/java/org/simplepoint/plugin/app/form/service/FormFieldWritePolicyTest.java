package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormFieldWritePolicyTest {
  private final FormRecordPayloadCompiler payloads = new FormRecordPayloadCompiler();
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void createOnlyRejectsEveryUpdatePayloadIncludingNullAndUnchangedValues() {
    var document = document(FormFieldWritePolicy.CREATE_ONLY, false, null);
    assertThat(payloads.compile(document, Map.of(), Map.of("name", "original"), null, FormRecordState.DRAFT))
        .containsEntry("name", "original");
    for (var state : FormRecordState.values()) {
      for (String value : new String[]{"forged", "original", null}) {
        assertThatThrownBy(() -> payloads.compile(document, Map.of("name", "original"),
            Collections.singletonMap("name", value), state, FormRecordState.SUBMITTED))
            .isInstanceOfSatisfying(ApplicationFormException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
      }
      assertThat(payloads.compile(document, Map.of("name", "original"), Map.of(), state, FormRecordState.SUBMITTED))
          .containsOnlyKeys("record_state");
    }
  }

  @Test
  void submissionUsesPersistedStateAndCanWriteTheFinalValueOnce() {
    var document = document(FormFieldWritePolicy.UNTIL_SUBMITTED, true, null);
    assertThat(payloads.compile(document, Map.of("name", "draft"), Map.of("name", "final"),
        FormRecordState.DRAFT, FormRecordState.SUBMITTED)).containsEntry("name", "final");
    assertThatThrownBy(() -> payloads.compile(document, Map.of("name", "final"), Map.of("name", "forged"),
        FormRecordState.SUBMITTED, FormRecordState.SUBMITTED)).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> payloads.compile(document, Map.of("name", "final"), Map.of("name", "forged"),
        FormRecordState.SUBMITTED, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    assertThat(payloads.compile(document, Map.of("name", "final"), Map.of(),
        FormRecordState.SUBMITTED, FormRecordState.SUBMITTED)).containsOnlyKeys("record_state");
    // Receipt lookup must preserve the exact old intent without validating today's locked state.
    assertThat(payloads.project(document, Map.of("name", "final"), FormRecordState.SUBMITTED))
        .containsEntry("name", "final");
  }

  @Test
  void requiredCreateOnlyFieldsCannotBecomePermanentlyEmptyAfterDraftCreation() {
    var document = document(FormFieldWritePolicy.CREATE_ONLY, true, null);
    assertThatThrownBy(() -> payloads.compile(document, Map.of(), Map.of(), null, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> payloads.compile(document, Map.of(), Map.of("name", ""), null, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    var defaulted = document(FormFieldWritePolicy.CREATE_ONLY, true, new FormValue(FormValueKind.STRING, "default"));
    assertThat(payloads.compile(defaulted, Map.of(), Map.of(), null, FormRecordState.DRAFT))
        .containsOnlyKeys("record_state");
    assertThatThrownBy(() -> payloads.compile(defaulted, Map.of(), Collections.singletonMap("name", null),
        null, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void conditionalCreateOnlyRequirementsApplyBeforeFirstSaveIncludingAuxiliaryScenes() {
    final var name = document(FormFieldWritePolicy.CREATE_ONLY, false, null).fields().getFirst();
    final var kind = new FormFieldDefinition("kind", "kind", "Kind", "Kind", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 120, null, null, null, List.of());
    var input = new FormComponentDefinition("input", "field.text", "1", "name", 24,
        null, null, null, null, false, Map.of(), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of(input));
    final var condition = new FormCondition(FormConditionOperator.EQUALS, "kind",
        List.of(new FormValue(FormValueKind.STRING, "person")), List.of());
    var required = new FormRuleDefinition("required", "input", FormRuleEffect.REQUIRE, condition);
    for (FormSceneType scene : FormSceneType.values()) {
      var source = new ApplicationFormDocument("1", List.of(name, kind), List.of(),
          List.of(new FormSceneDefinition("scene", "scene", "Scene", scene, null, root)), List.of(required));
      assertThatThrownBy(() -> payloads.compile(source, Map.of(), Map.of("kind", "person"), null, FormRecordState.DRAFT))
          .isInstanceOf(ApplicationFormException.class).hasMessageContaining("before saving: name");
      assertThat(payloads.compile(source, Map.of(), Map.of("kind", "company"), null, FormRecordState.DRAFT))
          .containsEntry("record_state", "DRAFT");
      assertThat(payloads.compile(source, Map.of(), Map.of("kind", "person", "name", "Ada"), null, FormRecordState.DRAFT))
          .containsEntry("name", "Ada");
      var hidden = new ApplicationFormDocument("1", source.fields(), List.of(), source.scenes(),
          List.of(required, new FormRuleDefinition("hidden", "root", FormRuleEffect.HIDE, condition)));
      assertThat(payloads.compile(hidden, Map.of(), Map.of("kind", "person"), null, FormRecordState.DRAFT))
          .containsEntry("record_state", "DRAFT");
    }
  }

  @Test
  void policyChangesOnlyDocumentChecksumAndLegacyDocumentsKeepTheirCanonicalForm() throws Exception {
    var codec = new FormDocumentCodec(mapper);
    var legacy = document(null, false, null);
    var encoded = codec.encode(legacy);
    assertThat(encoded.json()).doesNotContain("writePolicy");
    assertThat(codec.encode(document(FormFieldWritePolicy.EDITABLE, false, null))).isEqualTo(encoded);
    assertThat(codec.encode(codec.decode(encoded.json()))).isEqualTo(encoded);
    var protectedDocument = document(FormFieldWritePolicy.CREATE_ONLY, false, null);
    var protectedEncoded = codec.encode(protectedDocument);
    assertThat(protectedEncoded.checksum()).isNotEqualTo(encoded.checksum());
    assertThat(codec.decode(protectedEncoded.json()).fields().getFirst().effectiveWritePolicy())
        .isEqualTo(FormFieldWritePolicy.CREATE_ONLY);
    var storage = new FormStorageProjectionCompiler(mapper);
    assertThat(storage.compile(protectedDocument)).isEqualTo(storage.compile(legacy));
    assertThatThrownBy(() -> mapper.readValue(protectedEncoded.json().replace("CREATE_ONLY", "UNKNOWN"),
        ApplicationFormDocument.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  private static ApplicationFormDocument document(FormFieldWritePolicy policy, boolean required, FormValue defaultValue) {
    return new ApplicationFormDocument("1", List.of(new FormFieldDefinition(
        "name", "name", "Name", "Name", FormFieldType.STRING, FormStorageMode.STORED,
        required, false, 120, null, null, defaultValue, List.of(), policy)), List.of(), List.of(), List.of());
  }
}
