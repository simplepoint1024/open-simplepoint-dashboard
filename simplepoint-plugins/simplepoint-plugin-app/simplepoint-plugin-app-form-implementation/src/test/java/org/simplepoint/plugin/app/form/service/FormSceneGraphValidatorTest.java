package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;

class FormSceneGraphValidatorTest {

  @Test
  void acceptsSharedFieldsAcrossScenesAndConvergingNavigation() {
    FormSceneGraphValidator.validate(List.of(
        scene("main", field("name"), open("a"), open("b")),
        scene("a", field("name"), open("c")),
        scene("b", open("c")),
        scene("c", field("detail"))
    ));
  }

  @Test
  void rejectsAmbiguousFieldsWithinOneSceneAndNavigationCycles() {
    assertThatThrownBy(() -> FormSceneGraphValidator.validate(List.of(
        scene("main", field("name"), field("name"))
    ))).isInstanceOf(ApplicationFormException.class)
        .hasMessageContaining("only once");
    assertThatThrownBy(() -> FormSceneGraphValidator.validate(List.of(
        scene("main", open("a")), scene("a", open("b")), scene("b", open("a"))
    ))).isInstanceOf(ApplicationFormException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void enforcesTheSameNestingBudgetAsTheBrowser() {
    List<FormSceneDefinition> scenes = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      scenes.add(scene("scene-" + index, open("scene-" + (index + 1))));
    }
    scenes.add(scene("scene-8", field("name")));
    assertThatThrownBy(() -> FormSceneGraphValidator.validate(scenes))
        .isInstanceOf(ApplicationFormException.class)
        .hasMessageContaining("maximum nesting depth");
  }

  private static FormSceneDefinition scene(
      final String id,
      final FormComponentDefinition... children
  ) {
    return new FormSceneDefinition(id, id, id, FormSceneType.DIALOG, 640,
        node(id + "-root", "layout.form", null, null, List.of(children)));
  }

  private static FormComponentDefinition field(final String id) {
    return node(id + "-input", "field.text", id, null, List.of());
  }

  private static FormComponentDefinition open(final String target) {
    return node("open-" + target, "action.open-dialog", null, target, List.of());
  }

  private static FormComponentDefinition node(
      final String id,
      final String type,
      final String field,
      final String target,
      final List<FormComponentDefinition> children
  ) {
    return new FormComponentDefinition(id, type, "1", field, 24,
        null, null, null, target, false, Map.of(), children);
  }
}
