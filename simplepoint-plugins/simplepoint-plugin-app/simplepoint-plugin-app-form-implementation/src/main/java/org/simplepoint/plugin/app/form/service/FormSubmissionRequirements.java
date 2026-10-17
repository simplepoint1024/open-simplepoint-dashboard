package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

/** Compiles published scene rules once into immutable, field-oriented submission predicates. */
final class FormSubmissionRequirements {

  private FormSubmissionRequirements() {
  }

  /** Preserves ordered rule overrides, ancestor visibility and the union of every scene's requirements. */
  static List<Requirement> compile(final ApplicationFormDocument document) {
    Map<String, FormFieldDefinition> fields = new HashMap<>();
    document.fields().forEach(field -> fields.put(field.fieldId(), field));
    Map<String, FormFieldType> types = document.fields().stream().collect(
        java.util.stream.Collectors.toUnmodifiableMap(FormFieldDefinition::fieldId, FormFieldDefinition::type));
    Map<String, ComponentState> states = new HashMap<>();
    document.scenes().forEach(scene -> collect(scene.root(), fields, states));
    for (FormRuleDefinition rule : document.rules()) {
      ComponentState state = states.get(rule.targetComponentId());
      if (state == null) {
        continue;
      }
      Predicate condition = new Condition(rule.condition(), types);
      switch (rule.effect()) {
        case SHOW -> state.visible = condition;
        case HIDE -> state.visible = not(condition);
        case REQUIRE -> state.required = condition;
        case OPTIONAL -> state.required = not(condition);
        default -> {
          // Editing affordances cannot remove a server-side submission requirement.
        }
      }
    }
    Map<String, List<Predicate>> occurrences = new HashMap<>();
    document.scenes().forEach(scene -> project(scene.root(), Constant.TRUE, states, occurrences));
    return document.fields().stream().filter(FormSubmissionRequirements::persistent).map(field -> {
      List<Predicate> predicates = occurrences.get(field.fieldId());
      Predicate required = predicates == null ? constant(field.submitRequired()) : any(predicates);
      return new Requirement(field, required);
    }).toList();
  }

  private static void collect(final FormComponentDefinition component,
      final Map<String, FormFieldDefinition> fields, final Map<String, ComponentState> states) {
    FormFieldDefinition field = fields.get(component.fieldId());
    states.put(component.componentId(), new ComponentState(constant(field != null && field.submitRequired())));
    component.children().forEach(child -> collect(child, fields, states));
  }

  private static void project(final FormComponentDefinition component, final Predicate parentVisible,
      final Map<String, ComponentState> states, final Map<String, List<Predicate>> occurrences) {
    ComponentState state = states.get(component.componentId());
    Predicate visible = all(List.of(parentVisible, state.visible));
    if (component.fieldId() != null) {
      occurrences.computeIfAbsent(component.fieldId(), ignored -> new ArrayList<>())
          .add(all(List.of(visible, state.required)));
    }
    component.children().forEach(child -> project(child, visible, states, occurrences));
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.storageMode() == FormStorageMode.STORED || field.storageMode() == FormStorageMode.REFERENCE
        || field.storageMode() == FormStorageMode.ATTACHMENT;
  }

  private static Constant constant(final boolean value) {
    return value ? Constant.TRUE : Constant.FALSE;
  }

  private static Predicate not(final Predicate source) {
    if (source instanceof Constant value) {
      return constant(value == Constant.FALSE);
    }
    return source instanceof Negated negated ? negated.child() : new Negated(source);
  }

  private static Predicate all(final List<Predicate> source) {
    if (source.contains(Constant.FALSE)) {
      return Constant.FALSE;
    }
    List<Predicate> children = source.stream().filter(child -> child != Constant.TRUE).distinct().toList();
    if (children.stream().anyMatch(child -> children.contains(not(child)))) {
      return Constant.FALSE;
    }
    return children.isEmpty() ? Constant.TRUE : children.size() == 1 ? children.getFirst() : new All(children);
  }

  private static Predicate any(final List<Predicate> source) {
    if (source.contains(Constant.TRUE)) {
      return Constant.TRUE;
    }
    List<Predicate> children = source.stream().filter(child -> child != Constant.FALSE).distinct().toList();
    if (children.stream().anyMatch(child -> children.contains(not(child)))) {
      return Constant.TRUE;
    }
    return children.isEmpty() ? Constant.FALSE : children.size() == 1 ? children.getFirst() : new Any(children);
  }

  /** A field has one requirement even if it appears in multiple scenes. */
  record Requirement(FormFieldDefinition field, Predicate predicate) {
    /** The same invariant governs a saved state and publication inspection of that state. */
    Requirement forState(final FormRecordState state) {
      if (field.effectiveWritePolicy() == FormFieldWritePolicy.CREATE_ONLY && field.submitRequired()) {
        return new Requirement(field, Constant.TRUE);
      }
      return state == FormRecordState.SUBMITTED || !field.allowsWrite(state)
          ? this : new Requirement(field, Constant.FALSE);
    }

    boolean missing(final Map<String, Object> values) {
      return predicate.matches(values) && FormRuleEvaluator.empty(values.get(field.fieldId()), field.type());
    }
  }

  /** Structural predicates can be inspected by publication analysis without executing a UI renderer. */
  sealed interface Predicate permits Constant, Condition, Negated, All, Any {
    boolean matches(Map<String, Object> values);
  }

  enum Constant implements Predicate {
    TRUE, FALSE;

    @Override
    public boolean matches(final Map<String, Object> values) {
      return this == TRUE;
    }
  }

  record Condition(FormCondition condition, Map<String, FormFieldType> types) implements Predicate {
    Condition {
      Objects.requireNonNull(condition, "condition");
      types = Map.copyOf(types);
    }

    @Override
    public boolean matches(final Map<String, Object> values) {
      return FormRuleEvaluator.matches(condition, values, types);
    }
  }

  record Negated(Predicate child) implements Predicate {
    Negated {
      Objects.requireNonNull(child, "child");
    }

    @Override
    public boolean matches(final Map<String, Object> values) {
      return !child.matches(values);
    }
  }

  record All(List<Predicate> children) implements Predicate {
    All {
      children = List.copyOf(children);
    }

    @Override
    public boolean matches(final Map<String, Object> values) {
      return children.stream().allMatch(child -> child.matches(values));
    }
  }

  record Any(List<Predicate> children) implements Predicate {
    Any {
      children = List.copyOf(children);
    }

    @Override
    public boolean matches(final Map<String, Object> values) {
      return children.stream().anyMatch(child -> child.matches(values));
    }
  }

  private static final class ComponentState {
    private Predicate visible = Constant.TRUE;
    private Predicate required;

    private ComponentState(final Predicate required) {
      this.required = required;
    }
  }
}
