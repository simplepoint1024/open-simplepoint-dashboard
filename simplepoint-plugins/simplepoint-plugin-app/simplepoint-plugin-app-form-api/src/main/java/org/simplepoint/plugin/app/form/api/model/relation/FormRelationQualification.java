package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/**
 * Business admission, independent of a scene's candidate filtering. Fields belong to the target.
 */
public record FormRelationQualification(FormCondition condition, Recheck recheck) {
  public enum Recheck {
    ON_CHANGE,
    ON_SUBMIT,
    ALWAYS
  }

  public FormRelationQualification {
    Objects.requireNonNull(condition, "Relation qualification condition is required");
    Objects.requireNonNull(recheck, "Relation qualification recheck policy is required");
    validateCondition(condition);
  }

  static void validateCondition(FormCondition condition) {
    validate(condition, 1, new int[] {0});
  }

  private static void validate(FormCondition condition, int depth, int[] nodes) {
    if (condition == null || condition.operator() == null || depth > 4 || ++nodes[0] > 32) {
      throw new IllegalArgumentException("Relation qualification exceeds its condition budget");
    }
    var op = condition.operator();
    boolean group =
        op == FormConditionOperator.AND
            || op == FormConditionOperator.OR
            || op == FormConditionOperator.NOT;
    if (group) {
      if (condition.fieldId() != null
          || !condition.values().isEmpty()
          || condition.children().isEmpty()
          || op == FormConditionOperator.NOT && condition.children().size() != 1) {
        throw new IllegalArgumentException("Invalid relation qualification group");
      }
      condition.children().forEach(child -> validate(child, depth + 1, nodes));
      return;
    }
    FormRelationIdentity.require(condition.fieldId());
    boolean empty =
        op == FormConditionOperator.IS_EMPTY || op == FormConditionOperator.IS_NOT_EMPTY;
    boolean multiple = op == FormConditionOperator.IN || op == FormConditionOperator.NOT_IN;
    int count = condition.values().size();
    if (!condition.children().isEmpty()
        || empty && count != 0
        || !empty && (count == 0 || count > (multiple ? 100 : 1))) {
      throw new IllegalArgumentException("Invalid relation qualification predicate");
    }
    for (var value : condition.values()) {
      if (value == null
          || value.kind() == null
          || value.kind() == FormValueKind.NULL
          || value.value() == null
          || value.value().length() > 2000) {
        throw new IllegalArgumentException("Invalid relation qualification literal");
      }
    }
  }
}
