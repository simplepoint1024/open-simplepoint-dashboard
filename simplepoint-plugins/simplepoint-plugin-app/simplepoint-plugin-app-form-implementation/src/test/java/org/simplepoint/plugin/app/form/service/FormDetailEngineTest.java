package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

class FormDetailEngineTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final FormDocumentValidator validator = new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void canonicalDocumentIncludesChildDataAndPreservesLegacyBytes() throws Exception {
    var codec = new FormDocumentCodec(mapper);
    var empty = new ApplicationFormDocument("1", List.of(), List.of(), document().scenes(), List.of());
    assertThat(codec.encode(empty).json()).doesNotContain("details");
    assertThat(codec.encode(document()).checksum()).isNotEqualTo(codec.encode(empty).checksum());
    assertThat(codec.decode(codec.encode(document()).json()).details()).isEqualTo(document().details());
    validator.validate(document());
    var missing = new ApplicationFormDocument("1", List.of(), List.of(), document().scenes(), List.of());
    assertThatThrownBy(() -> validator.validate(missing)).isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void compilesAnOwnedTableWithParentScopedIndexesAndNoRootFields() {
    var compiler = new FormStorageProjectionCompiler(mapper);
    var projection = compiler.compileDetail(detail(), "parent-model");
    assertThat(projection.schema().owner().parentModelId()).isEqualTo("parent-model");
    assertThat(projection.schema().fields()).extracting(field -> field.code()).containsExactly("parent_record_id", "row_order", "name");
    assertThat(projection.schema().indexes().get(1).fieldCodes()).containsExactly("parent_record_id", "name");
    assertThat(compiler.compile(document()).schema().fields()).hasSize(1);
  }

  @Test
  void mapsTypedRowsAndRejectsForeignIdentityMissingRequiredAndInvalidShapes() {
    var coordinator = new FormDetailRecordCoordinator(mock(DnaOwnedRecordService.class), new FormRecordPayloadCompiler(), mock(FormAttachmentReferenceValidator.class));
    var form = form();
    var values = Map.<String, Object>of("lines", List.of(Map.of("values", Map.of("name", "中文・日本語"))));
    assertThat(coordinator.parentValues(form, values)).isEmpty();
    var command = coordinator.intent(form, new RecordCreateMutation("parent", Map.of("record_state", "SUBMITTED")), "create", values, FormRecordState.SUBMITTED);
    assertThat(command.details().getFirst().model().physicalModelId()).isEqualTo("child-model");
    assertThat(command.details().getFirst().rows().getFirst().values()).containsOnlyKeys("name");
    coordinator.validate(form, Map.of(), values, null, FormRecordState.SUBMITTED);
    assertThatThrownBy(() -> coordinator.validate(form, Map.of(), Map.of("lines", List.of(Map.of("values", Map.of()))), null, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> coordinator.validate(form, Map.of(), Map.of("lines", List.of(Map.of("id", "foreign", "version", 0, "values", Map.of("name", "x")))), FormRecordState.DRAFT, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormDetailRecordCoordinator.parseRows(List.of(Map.of("id", "x", "version", 1.1, "values", Map.of()))))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static FormDetailDefinition detail() {
    var field = new FormFieldDefinition("name", "name", "Name", "Name", FormFieldType.STRING,
        FormStorageMode.STORED, true, false, 128, null, null, null, List.of());
    return new FormDetailDefinition("lines", "lines", "Lines", List.of(field), List.of(new FormIndexDefinition("unique-name", "unique_name", true, List.of("name"))));
  }
  private static ApplicationFormDocument document() {
    var child = new FormComponentDefinition("detail", "data.detail", "1", null, 24, "Lines", null, null, null, false,
        Map.of("detailId", new FormValue(FormValueKind.STRING, "lines")), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24, null, null, null, null, false, Map.of(), List.of(child));
    return new ApplicationFormDocument("1", List.of(), List.of(), List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), List.of(), List.of(detail()));
  }
  private static PublishedApplicationForm form() {
    return new PublishedApplicationForm("tenant", "app", "page", "Page", "form", "Form", 1, "resource", "parent-model", 1,
        "a".repeat(64), document(), 1, 0, List.of(new FormDetailBinding("lines", "child-model", 1, "b".repeat(64))));
  }
}
