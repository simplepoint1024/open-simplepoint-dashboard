package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.*;

class FormDetailEvolutionPolicyTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final FormStorageProjectionCompiler compiler = new FormStorageProjectionCompiler(mapper);

  @Test void translatedAndRenamedLabelsCanPublishWithoutChangingOwnedStorage() throws Exception {
    var previous = document();
    var tree = mapper.valueToTree(previous);
    var detail = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("details").get(0);
    detail.put("label", "工作明细");
    detail.set("labelLocalization", mapper.valueToTree(localized("工作明细", "Work items")));
    var field = (com.fasterxml.jackson.databind.node.ObjectNode) detail.path("fields").get(0);
    field.put("label", "工作类别");
    field.set("labelLocalization", mapper.valueToTree(localized("工作类别", "Category")));
    var choice = (com.fasterxml.jackson.databind.node.ObjectNode) field.path("choices").get(0);
    choice.put("label", "项目工作");
    choice.set("labelLocalization", mapper.valueToTree(localized("项目工作", "Project work")));
    var next = mapper.treeToValue(tree, ApplicationFormDocument.class);
    assertThatCode(() -> FormDetailEvolutionPolicy.validate(previous, next, compiler)).doesNotThrowAnyException();
    assertThat(compiler.compileDetail(next.details().getFirst(), "owner").checksum())
        .isEqualTo(compiler.compileDetail(previous.details().getFirst(), "owner").checksum());
    assertThat(previous.details().getFirst().fields().getFirst().labelLocalization()).isNull();
  }

  @Test void storageAndBehaviorChangesStillRequireReview() throws Exception {
    for (String property : List.of("storageCode", "length", "submitRequired", "choices", "calculation", "validation", "access")) {
      var previous = document();
      var tree = mapper.valueToTree(previous);
      var field = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("details").get(0).path("fields").get(0);
      switch (property) {
        case "storageCode" -> field.put(property, "changed");
        case "length" -> field.put(property, 500);
        case "submitRequired" -> field.put(property, true);
        case "choices" -> ((com.fasterxml.jackson.databind.node.ObjectNode) field.path("choices").get(0)).put("value", "another");
        case "calculation" -> field.set(property, mapper.readTree("{\"languageVersion\":\"1\",\"expression\":\"1\"}"));
        case "validation" -> field.set(property, mapper.readTree("{\"minLength\":2}"));
        case "access" -> field.set(property, mapper.readTree("{\"readOnly\":true}"));
      }
      var next = mapper.treeToValue(tree, ApplicationFormDocument.class);
      assertThatThrownBy(() -> FormDetailEvolutionPolicy.validate(previous, next, compiler))
          .as(property).isInstanceOf(ApplicationFormException.class);
    }
  }

  private ApplicationFormDocument document() {
    var field = new FormFieldDefinition("category", "category", "类别", "类别", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 100, null, null, null, List.of(new FormChoiceOption("project", "项目")));
    return new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of(),
        List.of(new FormDetailDefinition("items", "items", "明细", List.of(field), List.of())));
  }
  private LocalizedText localized(String value, String english) {
    return new LocalizedText(value, "zh-CN", Map.of("en-US", english));
  }
}
