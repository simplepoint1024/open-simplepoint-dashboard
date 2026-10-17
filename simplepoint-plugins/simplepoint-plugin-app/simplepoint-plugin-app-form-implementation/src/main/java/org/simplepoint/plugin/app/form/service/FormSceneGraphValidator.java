package org.simplepoint.plugin.app.form.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;

/** Validates bounded scene navigation and unambiguous per-scene field ownership. */
final class FormSceneGraphValidator {

  private static final int MAX_OPEN_SCENES = 8;

  private FormSceneGraphValidator() {
  }

  static void validate(final List<FormSceneDefinition> scenes) {
    Map<String, Set<String>> edges = new HashMap<>();
    for (FormSceneDefinition scene : scenes) {
      Set<String> targets = new HashSet<>();
      collect(scene.root(), new HashSet<>(), targets);
      edges.put(scene.sceneId(), targets);
    }
    Map<String, Integer> depths = new HashMap<>();
    for (String scene : edges.keySet()) {
      visit(scene, edges, new HashSet<>(), depths);
    }
  }

  private static void collect(
      final FormComponentDefinition component,
      final Set<String> fields,
      final Set<String> targets
  ) {
    if (component.fieldId() != null && !fields.add(component.fieldId())) {
      throw invalid("A field can appear only once in each scene");
    }
    if (component.targetSceneId() != null) {
      targets.add(component.targetSceneId());
    }
    component.children().forEach(child -> collect(child, fields, targets));
  }

  private static int visit(
      final String scene,
      final Map<String, Set<String>> edges,
      final Set<String> active,
      final Map<String, Integer> depths
  ) {
    Integer known = depths.get(scene);
    if (known != null) {
      return known;
    }
    if (!active.add(scene)) {
      throw invalid("Scene-opening actions cannot form a cycle");
    }
    int depth = 1;
    for (String target : edges.getOrDefault(scene, Set.of())) {
      depth = Math.max(depth, 1 + visit(target, edges, active, depths));
    }
    if (depth > MAX_OPEN_SCENES) {
      throw invalid("Scene navigation exceeds the maximum nesting depth");
    }
    active.remove(scene);
    depths.put(scene, depth);
    return depth;
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
