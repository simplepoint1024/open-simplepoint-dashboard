package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormComponentPropertyDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.service.FormComponentRegistry;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Validates a complete bounded form document without executing user-controlled code. */
public class FormDocumentValidator {

  static final int MAX_FIELDS = 300;
  static final int MAX_INDEXES = 50;
  static final int MAX_SCENES = 50;
  static final int MAX_RULES = 500;
  static final int MAX_COMPONENTS = 1000;
  static final int MAX_COMPONENT_DEPTH = 12;
  static final int MAX_CONDITION_NODES = 32;
  static final int MAX_CONDITION_DEPTH = 4;

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,62}");
  private static final Pattern PROPERTY_KEY = Pattern.compile("[a-z][A-Za-z0-9_.-]{0,62}");
  private static final Set<String> RUNTIME_FIELD_IDS = Set.of("id", "version", "recordState");
  private static final Set<String> SYSTEM_CODES =
      Set.of(
          "id",
          "tenant_id",
          "record_version",
          "record_state",
          "created_by",
          "created_org_dept_id",
          "created_at",
          "updated_by",
          "updated_at",
          "deleted_at");

  private final FormComponentRegistry componentRegistry;

  /** Creates the validator from the trusted component registry. */
  public FormDocumentValidator(final FormComponentRegistry componentRegistry) {
    this.componentRegistry = componentRegistry;
  }

  /** Validates one full form snapshot or throws a stable domain failure. */
  public void validate(final ApplicationFormDocument document) {
    if (document == null) {
      invalid("Form document is required");
    }
    if (!ApplicationFormApiVersion.DOCUMENT_V1.equals(document.contractVersion())) {
      invalid("Unsupported form document contract version");
    }
    if (document.fields().size() > MAX_FIELDS
        || document.indexes().size() > MAX_INDEXES
        || document.scenes().isEmpty()
        || document.scenes().size() > MAX_SCENES
        || document.rules().size() > MAX_RULES) {
      invalid("Form document exceeds its field, scene, or rule budget");
    }

    Map<String, FormFieldDefinition> fields = validateFields(document.fields());
    validateIndexes(document.indexes(), fields);
    if (document.presentation() != null) {
      var presentation = document.presentation();
      if (presentation.listFieldOrder().size() != new HashSet<>(presentation.listFieldOrder()).size()
          || !fields.keySet().containsAll(presentation.listFieldOrder())
          || presentation.labelWidth() != null && (presentation.labelWidth() < 60 || presentation.labelWidth() > 240)) {
        invalid("Form presentation contains invalid field order or label width");
      }
    }
    SceneValidation scenes = validateScenes(document.scenes(), fields);
    validateRules(document.rules(), fields, scenes);
    validateDetails(document);
    FormAggregateValidation.validateDefinition(document);
    FormComponentValues.validateDefinition(document);
  }

  private void validateDetails(final ApplicationFormDocument document) {
    if (document.details().size() > 5) {
      invalid("A form supports at most five detail collections");
    }
    Set<String> identities = new HashSet<>();
    Set<String> codes = new HashSet<>();
    for (var detail : document.details()) {
      if (!validId(detail.detailId())
          || !identities.add(detail.detailId())
          || document.fields().stream().anyMatch(field -> field.fieldId().equals(detail.detailId()))
          || detail.storageCode() == null
          || !detail.storageCode().matches("[a-z][a-z0-9_]{0,47}")
          || !codes.add(detail.storageCode())) {
        invalid("Detail identity or storage code is invalid");
      }
      requireText(detail.label(), 120, "Detail label is invalid");
      if (detail.fields().isEmpty() || detail.fields().size() > 48 || detail.indexes().size() > 8) {
        invalid("A detail requires 1 to 48 fields and at most eight indexes");
      }
      if (detail.fields().stream()
          .anyMatch(
              field ->
                  field.storageMode() != FormStorageMode.STORED
                      || "parent_record_id".equals(field.storageCode())
                      || "row_order".equals(field.storageCode())
                      || "owned_parent".equals(field.fieldId())
                      || "owned_order".equals(field.fieldId()))) {
        invalid("Detail fields must use stored values and cannot use ownership column names");
      }
      if (detail.indexes().stream()
          .anyMatch(index -> "owned_parent_order".equals(index.storageCode()))) {
        invalid("Detail indexes cannot use the ownership index name");
      }
      validateIndexes(detail.indexes(), validateFields(detail.fields()));
    }
    for (var scene : document.scenes()) {
      validateDetailBindings(scene.root(), identities, new HashSet<>());
    }
  }

  private static void validateDetailBindings(
      final FormComponentDefinition component,
      final Set<String> identities,
      final Set<String> bound) {
    if ("data.detail".equals(component.componentType())) {
      var binding = component.properties().get("detailId");
      if (binding == null || !identities.contains(binding.value()) || !bound.add(binding.value())) {
        invalid("A detail component must bind one defined collection once per scene");
      }
    }
    component.children().forEach(child -> validateDetailBindings(child, identities, bound));
  }

  /** Validates the stronger invariants required by a new immutable runtime version. */
  public void validatePublication(final ApplicationFormDocument document) {
    validate(document);
    FormPublicationValidator.validate(document);
  }

  private Map<String, FormFieldDefinition> validateFields(
      final List<FormFieldDefinition> definitions) {
    Map<String, FormFieldDefinition> fields = new HashMap<>();
    Set<String> codes = new HashSet<>();
    for (FormFieldDefinition field : definitions) {
      if (field == null
          || !validId(field.fieldId())
          || RUNTIME_FIELD_IDS.contains(field.fieldId())
          || org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.properties().contains(field.fieldId())) {
        invalid("Form field identity is invalid");
      }
      if (fields.putIfAbsent(field.fieldId(), field) != null) {
        invalid("Form field identity is duplicated");
      }
      if (field.type() == null || field.storageMode() == null) {
        invalid("Form field type and storage mode are required");
      }
      requireText(field.label(), 120, "Form field label is invalid");
      validateStorageCode(field, codes);
      validateStorageLabel(field);
      validateStorageMode(field);
      if (field.writePolicy() != null
          && (field.storageMode() == FormStorageMode.COMPUTED
              || field.storageMode() == FormStorageMode.TRANSIENT)) {
        invalid("Business write policies require a persistent field");
      }
      validateTypeModifiers(field);
      try {FormFieldValidations.definition(field);} catch (IllegalArgumentException error) {
        invalid("Form field business validation is invalid");
      }
      validateChoices(field);
      if (field.defaultValue() != null) {
        validateValue(field.type(), field.defaultValue(), "Form field default value is invalid");
        if (field.defaultValue().kind() != FormValueKind.NULL) {
          validateLiteralConstraints(field, field.defaultValue().value());
          try {FormFieldValidations.value(field, field.defaultValue().value());} catch (IllegalArgumentException error) {
            invalid("Form field default does not satisfy business validation");
          }
        }
      }
      validateStorageConstraint(field);
    }
    return Map.copyOf(fields);
  }

  private static void validateStorageCode(
      final FormFieldDefinition field, final Set<String> codes) {
    boolean persisted =
        field.storageMode() == FormStorageMode.STORED
            || field.storageMode() == FormStorageMode.REFERENCE
            || field.storageMode() == FormStorageMode.ATTACHMENT;
    if (persisted) {
      if (field.storageCode() == null
          || !CODE.matcher(field.storageCode()).matches()
          || SYSTEM_CODES.contains(field.storageCode())
          || !codes.add(field.storageCode())) {
        invalid("Persistent form field storage code is invalid or duplicated");
      }
    } else if (field.storageCode() != null && !field.storageCode().isBlank()) {
      invalid("Non-persistent form field cannot declare a storage code");
    }
  }

  private static void validateStorageConstraint(final FormFieldDefinition field) {
    if (!field.storageRequired()) {
      return;
    }
    boolean persisted =
        field.storageMode() == FormStorageMode.STORED
            || field.storageMode() == FormStorageMode.REFERENCE
            || field.storageMode() == FormStorageMode.ATTACHMENT;
    if (!persisted
        || field.defaultValue() == null
        || field.defaultValue().kind() == FormValueKind.NULL) {
      invalid("A storage-required field must be persistent and have a non-null default value");
    }
  }

  private static void validateStorageLabel(final FormFieldDefinition field) {
    boolean persisted =
        field.storageMode() == FormStorageMode.STORED
            || field.storageMode() == FormStorageMode.REFERENCE
            || field.storageMode() == FormStorageMode.ATTACHMENT;
    if (persisted) {
      requireText(field.storageLabel(), 128, "Persistent form field storage label is invalid");
    } else if (field.storageLabel() != null && !field.storageLabel().isBlank()) {
      invalid("Non-persistent form field cannot declare a storage label");
    }
  }

  private static void validateStorageMode(final FormFieldDefinition field) {
    if (field.storageMode() == FormStorageMode.REFERENCE
        && field.type() != FormFieldType.STRING
        && field.type() != FormFieldType.UUID) {
      invalid("Reference fields must use STRING or UUID values");
    }
    if (field.storageMode() == FormStorageMode.ATTACHMENT && field.type() != FormFieldType.JSON) {
      invalid("Attachment fields must use JSON references");
    }
  }

  private static void validateTypeModifiers(final FormFieldDefinition field) {
    if (field.type() == FormFieldType.STRING) {
      if (field.length() == null || field.length() < 1 || field.length() > 65_535) {
        invalid("STRING field length must be between 1 and 65535");
      }
    } else if (field.length() != null) {
      invalid("Only STRING fields accept a length");
    }
    if (field.type() == FormFieldType.DECIMAL) {
      if (field.precision() == null
          || field.precision() < 1
          || field.precision() > 38
          || field.scale() == null
          || field.scale() < 0
          || field.scale() > field.precision()) {
        invalid("DECIMAL field precision or scale is invalid");
      }
    } else if (field.precision() != null || field.scale() != null) {
      invalid("Only DECIMAL fields accept precision and scale");
    }
  }

  private static void validateChoices(final FormFieldDefinition field) {
    List<FormChoiceOption> choices = field.choices();
    if (choices.size() > 200) {
      invalid("Form field choice budget is exceeded");
    }
    Set<String> values = new HashSet<>();
    for (FormChoiceOption choice : choices) {
      if (choice == null
          || choice.value() == null
          || choice.value().isBlank()
          || choice.value().length() > 256) {
        invalid("Form field choice value is invalid or duplicated");
      }
      try {
        String canonical = FormChoiceValues.canonical(field.type(), choice.value());
        validateLiteralConstraints(field, canonical);
        if (!values.add(canonical)) {
          invalid("Form field choices have duplicate semantic values");
        }
      } catch (IllegalArgumentException | ArithmeticException exception) {
        invalid("Form field choice does not match its logical type");
      }
      requireText(choice.label(), 120, "Form field choice label is invalid");
    }
    if (!choices.isEmpty()
        && field.defaultValue() != null
        && field.defaultValue().kind() != FormValueKind.NULL) {
      try {
        if (!values.contains(
            FormChoiceValues.canonical(field.type(), field.defaultValue().value()))) {
          invalid("Form field default must belong to its choices");
        }
      } catch (IllegalArgumentException | ArithmeticException exception) {
        invalid("Form field choice default is invalid");
      }
    }
  }

  private static void validateLiteralConstraints(
      final FormFieldDefinition field, final String literal) {
    try {
      FormChoiceValues.validateStoredTemporal(field, literal);
      if (field.type() == FormFieldType.STRING && literal.codePointCount(0, literal.length()) > field.length()
          || field.type() == FormFieldType.DECIMAL
              && new BigDecimal(FormChoiceValues.canonical(FormFieldType.DECIMAL, literal))
                      .setScale(field.scale(), java.math.RoundingMode.UNNECESSARY)
                      .precision()
                  > field.precision()) {
        invalid("Form field literal exceeds its storage constraints");
      }
    } catch (IllegalArgumentException | ArithmeticException exception) {
      invalid("Form field literal exceeds its storage constraints");
    }
  }

  private static void validateIndexes(
      final List<FormIndexDefinition> indexes, final Map<String, FormFieldDefinition> fields) {
    Set<String> ids = new HashSet<>();
    Set<String> codes = new HashSet<>();
    for (FormIndexDefinition index : indexes) {
      if (index == null
          || !validId(index.indexId())
          || !ids.add(index.indexId())
          || index.storageCode() == null
          || !CODE.matcher(index.storageCode()).matches()
          || !codes.add(index.storageCode())
          || index.fieldIds().isEmpty()
          || index.fieldIds().size() > 8
          || new HashSet<>(index.fieldIds()).size() != index.fieldIds().size()) {
        invalid("Form index identity, code, or field list is invalid");
      }
      for (String fieldId : index.fieldIds()) {
        FormFieldDefinition field = fields.get(fieldId);
        if (field == null || field.storageCode() == null) {
          invalid("Form index references a non-persistent field");
        }
      }
    }
  }

  private SceneValidation validateScenes(
      final List<FormSceneDefinition> definitions, final Map<String, FormFieldDefinition> fields) {
    Map<String, FormSceneType> sceneTypes = new HashMap<>();
    Set<String> sceneCodes = new HashSet<>();
    Set<String> componentIds = new HashSet<>();
    Set<String> persistentInputs = new HashSet<>();
    int mainScenes = 0;
    int[] componentCount = {0};
    for (FormSceneDefinition scene : definitions) {
      if (scene == null
          || !validId(scene.sceneId())
          || sceneTypes.putIfAbsent(scene.sceneId(), scene.type()) != null
          || scene.code() == null
          || !CODE.matcher(scene.code()).matches()
          || !sceneCodes.add(scene.code())
          || scene.type() == null) {
        invalid("Form scene identity, code, or type is invalid");
      }
      requireText(scene.name(), 120, "Form scene name is invalid");
      if (scene.type() == FormSceneType.MAIN) {
        mainScenes++;
        if (scene.width() != null) {
          invalid("Main form scene cannot declare an overlay width");
        }
      } else if (scene.width() == null || scene.width() < 320 || scene.width() > 1600) {
        invalid("Dialog and drawer width must be between 320 and 1600");
      }
      FormComponentDescriptor rootDescriptor =
          scene.root() == null
              ? null
              : componentRegistry
                  .find(scene.root().componentType(), scene.root().componentVersion())
                  .orElse(null);
      if (rootDescriptor == null || !rootDescriptor.container()) {
        invalid("A form scene root must be a registered container component");
      }
      validateComponent(scene.root(), null, 1, fields, componentIds, componentCount);
      visit(
          scene.root(),
          component -> {
            FormFieldDefinition field =
                component.fieldId() == null ? null : fields.get(component.fieldId());
            if (field != null
                && Set.of(
                        FormStorageMode.STORED,
                        FormStorageMode.REFERENCE,
                        FormStorageMode.ATTACHMENT)
                    .contains(field.storageMode())) {
              persistentInputs.add(component.componentId());
            }
          });
    }
    if (mainScenes != 1) {
      invalid("A form document requires exactly one main scene");
    }
    validateSceneTargets(definitions, sceneTypes);
    FormSceneGraphValidator.validate(definitions);
    return new SceneValidation(Set.copyOf(componentIds), Set.copyOf(persistentInputs));
  }

  private void validateComponent(
      final FormComponentDefinition component,
      final FormComponentDescriptor parent,
      final int depth,
      final Map<String, FormFieldDefinition> fields,
      final Set<String> componentIds,
      final int[] componentCount) {
    if (component == null
        || !validId(component.componentId())
        || !componentIds.add(component.componentId())) {
      invalid("Form component identity is invalid or duplicated");
    }
    if (depth > MAX_COMPONENT_DEPTH || ++componentCount[0] > MAX_COMPONENTS) {
      invalid("Form component tree exceeds its depth or node budget");
    }
    final FormComponentDescriptor descriptor =
        componentRegistry
            .find(component.componentType(), component.componentVersion())
            .orElseThrow(() -> failure("Form component type is not registered"));
    if (parent != null
        && !descriptor.allowedParentTypes().isEmpty()
        && !descriptor.allowedParentTypes().contains(parent.type())) {
      invalid("Form component is not allowed under its parent component");
    }
    if (parent != null
        && !parent.allowedChildTypes().isEmpty()
        && !parent.allowedChildTypes().contains(descriptor.type())) {
      invalid("Form parent component does not allow this child component");
    }
    if (component.columnSpan() < 1 || component.columnSpan() > 24) {
      invalid("Form component column span must be between 1 and 24");
    }
    optionalText(component.label(), 120, "Form component label is invalid");
    optionalText(component.description(), 500, "Form component description is invalid");
    optionalText(component.placeholder(), 200, "Form component placeholder is invalid");
    validateComponentProperties(component, descriptor);
    componentRegistry.validate(component);

    if (descriptor.fieldRequired()) {
      FormFieldDefinition field = fields.get(component.fieldId());
      if (field == null || !descriptor.supportedFieldTypes().contains(field.type())) {
        invalid("Form component field binding is missing or incompatible");
      }
    } else if (component.fieldId() != null) {
      invalid("A non-field component cannot bind a form field");
    }
    if (!descriptor.container() && !component.children().isEmpty()) {
      invalid("A non-container component cannot contain children");
    }
    for (FormComponentDefinition child : component.children()) {
      validateComponent(child, descriptor, depth + 1, fields, componentIds, componentCount);
    }
  }

  private static void validateComponentProperties(
      final FormComponentDefinition component, final FormComponentDescriptor descriptor) {
    if (component.properties().size() > 32) {
      invalid("Form component property budget is exceeded");
    }
    for (Map.Entry<String, FormValue> property : component.properties().entrySet()) {
      if (property.getKey() == null
          || !PROPERTY_KEY.matcher(property.getKey()).matches()
          || property.getValue() == null) {
        invalid("Form component property is invalid");
      }
      FormComponentPropertyDescriptor propertyDescriptor =
          descriptor.propertySchema().get(property.getKey());
      if (propertyDescriptor == null
          || !propertyDescriptor.acceptedKinds().contains(property.getValue().kind())) {
        invalid("Form component property is not declared by its component contract");
      }
    }
    descriptor.propertySchema().values().stream()
        .filter(FormComponentPropertyDescriptor::required)
        .filter(property -> property.defaultValue() == null)
        .filter(property -> !component.properties().containsKey(property.name()))
        .findAny()
        .ifPresent(property -> invalid("A required form component property is missing"));
  }

  private static void validateSceneTargets(
      final List<FormSceneDefinition> scenes, final Map<String, FormSceneType> sceneTypes) {
    for (FormSceneDefinition scene : scenes) {
      visit(
          scene.root(),
          component -> {
            boolean opensDialog = "action.open-dialog".equals(component.componentType());
            boolean opensDrawer = "action.open-drawer".equals(component.componentType());
            FormSceneType targetType = sceneTypes.get(component.targetSceneId());
            if ((opensDialog || opensDrawer) && targetType == null) {
              invalid("Scene-opening action references an unknown scene");
            }
            if (opensDialog && targetType != FormSceneType.DIALOG
                || opensDrawer && targetType != FormSceneType.DRAWER) {
              invalid("Scene-opening action target has an incompatible scene type");
            }
            if (!opensDialog && !opensDrawer && component.targetSceneId() != null) {
              invalid("Only scene-opening actions may declare a target scene");
            }
            if (component.targetSceneId() != null
                && component.targetSceneId().equals(scene.sceneId())) {
              invalid("A scene cannot open itself");
            }
          });
    }
  }

  private void validateRules(
      final List<FormRuleDefinition> rules,
      final Map<String, FormFieldDefinition> fields,
      final SceneValidation scenes) {
    Set<String> ruleIds = new HashSet<>();
    for (FormRuleDefinition rule : rules) {
      if (rule == null
          || !validId(rule.ruleId())
          || !ruleIds.add(rule.ruleId())
          || rule.effect() == null
          || !scenes.componentIds().contains(rule.targetComponentId())) {
        invalid("Form rule identity, target, or effect is invalid");
      }
      if (Set.of(FormRuleEffect.REQUIRE, FormRuleEffect.OPTIONAL).contains(rule.effect())
          && !scenes.persistentInputs().contains(rule.targetComponentId())) {
        invalid("Required rules must target a persistent field input");
      }
      int[] nodes = {0};
      validateCondition(rule.condition(), 1, nodes, fields);
    }
  }

  private void validateCondition(
      final FormCondition condition,
      final int depth,
      final int[] nodes,
      final Map<String, FormFieldDefinition> fields) {
    if (condition == null
        || condition.operator() == null
        || depth > MAX_CONDITION_DEPTH
        || ++nodes[0] > MAX_CONDITION_NODES) {
      invalid("Form rule condition exceeds its shape or complexity budget");
    }
    boolean group =
        Set.of(FormConditionOperator.AND, FormConditionOperator.OR, FormConditionOperator.NOT)
            .contains(condition.operator());
    if (group) {
      if (condition.fieldId() != null
          || !condition.values().isEmpty()
          || condition.children().isEmpty()
          || condition.operator() == FormConditionOperator.NOT
              && condition.children().size() != 1) {
        invalid("Form rule condition group has an invalid shape");
      }
      for (FormCondition child : condition.children()) {
        validateCondition(child, depth + 1, nodes, fields);
      }
      return;
    }
    FormFieldDefinition field = fields.get(condition.fieldId());
    if (field == null || !condition.children().isEmpty()) {
      invalid("Form rule predicate references an unknown field");
    }
    int valueCount = condition.values().size();
    boolean emptyOperator =
        condition.operator() == FormConditionOperator.IS_EMPTY
            || condition.operator() == FormConditionOperator.IS_NOT_EMPTY;
    boolean setOperator =
        condition.operator() == FormConditionOperator.IN
            || condition.operator() == FormConditionOperator.NOT_IN;
    if (emptyOperator && valueCount != 0
        || setOperator && (valueCount < 1 || valueCount > 100)
        || !emptyOperator && !setOperator && valueCount != 1) {
      invalid("Form rule predicate value count is invalid");
    }
    boolean orderedComparison =
        Set.of(
                FormConditionOperator.GREATER_THAN,
                FormConditionOperator.GREATER_THAN_OR_EQUAL,
                FormConditionOperator.LESS_THAN,
                FormConditionOperator.LESS_THAN_OR_EQUAL)
            .contains(condition.operator());
    if (orderedComparison
        && !Set.of(
                FormFieldType.INT32,
                FormFieldType.INT64,
                FormFieldType.DECIMAL,
                FormFieldType.DATE,
                FormFieldType.TIME,
                FormFieldType.DATETIME)
            .contains(field.type())) {
      invalid("Ordered form rule predicate requires an ordered field type");
    }
    for (FormValue value : condition.values()) {
      if (value == null || value.kind() == FormValueKind.NULL) {
        invalid("Form rule predicate value is invalid");
      }
      validateValue(field.type(), value, "Form rule predicate value is invalid");
    }
  }

  private void validateValue(
      final FormFieldType type, final FormValue value, final String message) {
    if (value == null || value.kind() == FormValueKind.NULL) {
      return;
    }
    try {
      switch (type) {
        case STRING, TEXT -> requireKind(value, FormValueKind.STRING);
        case BOOLEAN -> {
          requireKind(value, FormValueKind.BOOLEAN);
          if (!Set.of("true", "false").contains(value.value())) {
            throw new IllegalArgumentException();
          }
        }
        case INT32 -> {
          requireKind(value, FormValueKind.INTEGER);
          Integer.parseInt(value.value());
        }
        case INT64 -> {
          requireKind(value, FormValueKind.INTEGER);
          Long.parseLong(value.value());
        }
        case DECIMAL -> {
          requireKind(value, FormValueKind.DECIMAL);
          FormChoiceValues.canonical(FormFieldType.DECIMAL, value.value());
        }
        case DATE -> {
          requireKind(value, FormValueKind.DATE);
          LocalDate.parse(value.value());
        }
        case TIME -> {
          requireKind(value, FormValueKind.TIME);
          LocalTime.parse(value.value());
        }
        case DATETIME -> {
          requireKind(value, FormValueKind.DATETIME);
          Instant.parse(value.value());
        }
        case UUID -> {
          requireKind(value, FormValueKind.UUID);
          UUID.fromString(value.value());
        }
        case JSON -> {
          requireKind(value, FormValueKind.JSON);
          LogicalJsonValues.canonicalize(value.value());
        }
        default -> throw new IllegalStateException("Unsupported form field type: " + type);
      }
    } catch (IllegalArgumentException | DateTimeException exception) {
      invalid(message);
    }
  }

  private static void requireKind(final FormValue value, final FormValueKind expected) {
    if (value.kind() != expected) {
      throw new IllegalArgumentException("Unexpected form value kind");
    }
  }

  private static void visit(
      final FormComponentDefinition component,
      final java.util.function.Consumer<FormComponentDefinition> visitor) {
    visitor.accept(component);
    component.children().forEach(child -> visit(child, visitor));
  }

  private static boolean validId(final String value) {
    return value != null && ID.matcher(value).matches();
  }

  private static void requireText(final String value, final int maxLength, final String message) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      invalid(message);
    }
  }

  private static void optionalText(final String value, final int maxLength, final String message) {
    if (value != null && (value.isBlank() || value.length() > maxLength)) {
      invalid(message);
    }
  }

  private static ApplicationFormException failure(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }

  private static void invalid(final String message) {
    throw failure(message);
  }

  private record SceneValidation(Set<String> componentIds, Set<String> persistentInputs) {
    private SceneValidation {
      Objects.requireNonNull(componentIds, "componentIds");
      Objects.requireNonNull(persistentInputs, "persistentInputs");
    }
  }
}
