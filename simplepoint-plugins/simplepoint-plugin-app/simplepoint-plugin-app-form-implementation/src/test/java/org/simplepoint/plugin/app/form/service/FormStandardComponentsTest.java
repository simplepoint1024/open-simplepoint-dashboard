package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.component.StandardComponentsContributor;

class FormStandardComponentsTest {
  private final StandardComponentsContributor contributor = new StandardComponentsContributor();
  private FormComponentDefinition component(String type, String key, FormValueKind kind, String value) {
    var descriptor = contributor.descriptors().stream().filter(item -> item.type().equals(type)).findFirst().orElseThrow();
    Map<String, FormValue> properties = new HashMap<>();
    descriptor.propertySchema().forEach((name, definition) -> properties.put(name, definition.defaultValue()));
    if (key != null) properties.put(key, new FormValue(kind, value));
    return new FormComponentDefinition("control", type, "1", descriptor.fieldRequired() ? "field" : null,
        24, "Component", null, null, null, false, properties, List.of());
  }
  private void write(FormComponentDefinition node, Object value) {
    var document = new ApplicationFormDocument("1", List.of(), List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, node)), List.of());
    FormComponentValues.validate(document, Map.of("field", value));
  }
  @Test void componentDefaultsValidateAndRegistrationIsUnique() {
    contributor.descriptors().forEach(descriptor -> contributor.validate(component(descriptor.type(), null, null, null)));
    var registry = new DefaultFormComponentRegistry();
    assertThat(registry.descriptors().stream().map(FormComponentDescriptor::type).distinct().count()).isEqualTo(registry.descriptors().size());
  }
  @Test void rejectsUnsafeUrlsAndUnboundedPresentation() {
    for (String url : List.of("javascript:alert(1)", "//bad.test", "data:image/svg+xml,test", "/\\bad.test", "https://user:pass@bad.test/a")) {
      assertThatThrownBy(() -> contributor.validate(component("display.image", "src", FormValueKind.STRING, url))).isInstanceOf(ApplicationFormException.class);
    }
    assertThatThrownBy(() -> contributor.validate(component("display.progress", "percent", FormValueKind.INTEGER, "101"))).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> contributor.validate(component("display.markdown", "text", FormValueKind.STRING, "x".repeat(16001)))).isInstanceOf(ApplicationFormException.class);
  }
  @Test void cascaderRequiresAnExistingCompletePathAndTransferUniqueExistingKeys() {
    var cascader = component("field.cascader", "options", FormValueKind.JSON,
        "[{\"value\":\"cn\",\"label\":\"中国\",\"children\":[{\"value\":\"bj\",\"label\":\"北京\"}]}]");
    contributor.validate(cascader);
    write(cascader, "[\"cn\",\"bj\"]");
    for (String value : List.of("[\"cn\"]", "[\"bj\"]", "[\"cn\",\"forged\"]", "[1]", "{}")) {
      assertThatThrownBy(() -> write(cascader, value)).isInstanceOf(ApplicationFormException.class);
    }
    var transfer = component("field.transfer", "options", FormValueKind.JSON, "[{\"value\":\"a\",\"label\":\"甲\"}]");
    write(transfer, "[\"a\"]");
    assertThatThrownBy(() -> write(transfer, "[\"a\",\"a\"]")).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> write(transfer, "[\"missing\"]")).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> contributor.validate(component("field.transfer", "options", FormValueKind.JSON,
        "[{\"value\":\"a\",\"label\":\"甲\",\"children\":[]}]"))).isInstanceOf(ApplicationFormException.class);
  }
  @Test void reusedFieldsCannotDeclareConflictingDomains() {
    var first = component("field.rating", "count", FormValueKind.INTEGER, "5");
    var second = component("field.rating", "count", FormValueKind.INTEGER, "10");
    var document = new ApplicationFormDocument("1", List.of(), List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, first),
        new FormSceneDefinition("dialog", "dialog", "Dialog", FormSceneType.DIALOG, 640, second)), List.of());
    assertThatThrownBy(() -> FormComponentValues.validateDefinition(document)).isInstanceOf(ApplicationFormException.class);
  }

  @Test void ratingBoundsCannotBeBypassedThroughTheApi() {
    var rating = component("field.rating", "count", FormValueKind.INTEGER, "5");
    write(rating, 0); write(rating, 5);
    for (Object value : List.of(-1, 6, 1.5, "NaN")) assertThatThrownBy(() -> write(rating, value)).isInstanceOf(ApplicationFormException.class);
  }
}
