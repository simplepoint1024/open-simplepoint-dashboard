package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordSchemaService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField;
import org.springframework.stereotype.Service;

/** Produces the dynamic platform table contract without exposing DNA storage identities. */
@Service
public class ApplicationFormRecordSchemaServiceImpl
    implements ApplicationFormRecordSchemaService {

  private static final Set<FormFieldType> TEXT_TYPES = Set.of(
      FormFieldType.STRING,
      FormFieldType.TEXT,
      FormFieldType.JSON
  );
  private static final Set<String> RECORD_ENVELOPE_FIELDS = Set.of(
      "id", "version", "recordState"
  );
  private static final List<String> NULL_OPERATORS = List.of("is:null", "is:not:null");
  private static final List<String> EQUALITY_OPERATORS = List.of(
      "equals", "not:equals", "in", "not:in", "is:null", "is:not:null"
  );
  private static final List<String> COMPARABLE_OPERATORS = List.of(
      "equals", "not:equals", "in", "not:in", "between", "not:between",
      "than:greater", "than:less", "than:equal:greater", "than:equal:less",
      "is:null", "is:not:null"
  );
  private static final List<String> TEXT_OPERATORS = List.of(
      "like", "not:like", "equals", "not:equals", "in", "not:in", "between",
      "not:between", "than:greater", "than:less", "than:equal:greater",
      "than:equal:less", "is:null", "is:not:null"
  );

  private final PublishedApplicationFormResolver resolver;
  private final FormRuntimeDocumentCompiler documents;
  private final DnaInvocationContextFactory contexts;

  /** Creates the schema projection over the same active binding used by record operations. */
  public ApplicationFormRecordSchemaServiceImpl(
      final PublishedApplicationFormResolver resolver,
      final FormRuntimeDocumentCompiler documents,
      final DnaInvocationContextFactory contexts
  ) {
    this.resolver = resolver;
    this.documents = documents;
    this.contexts = contexts;
  }

  @Override
  public Map<String, Object> schema(final String applicationId, final String pageId) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    final ApplicationFormRuntimeDocument document = documents.compile(form);
    final ApplicationFormRuntimeCapabilities capabilities = resolver.capabilities(form);
    final DnaInvocationContext context = contexts.forResource(form.resourceCode());
    final Map<String, FormFieldDefinition> storedFields = form.document().fields().stream()
        .filter(ApplicationFormRecordSchemaServiceImpl::persistent)
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            FormFieldDefinition::fieldId,
            field -> field
        ));
    final String permissionPrefix = RecordPermissionModel.key(form.physicalModelId()) + "#";
    final Set<String> editableFields = editableFields(document);
    Map<String, Object> properties = new LinkedHashMap<>();
    final Map<String, Object> queryFields = new LinkedHashMap<>();
    properties.put("id", systemProperty("i18n:app.runtime.record.id", "string", 0));
    properties.put("version", systemProperty("i18n:app.runtime.record.version", "integer", 1));
    properties.put("recordState", stateProperty(2));
    queryFields.put("recordState", queryField(FormFieldType.STRING));
    int order = 3;
    for (var field : RecordSystemField.values()) {
      String permission = context.fieldPermissions().get(permissionPrefix + field.storageCode());
      if ("HIDDEN".equals(permission)) {
        continue;
      }
      Map<String, Object> property = new LinkedHashMap<>(systemProperty(
          "i18n:app.runtime.record." + field.property(), "string", order++));
      property.put("type", List.of("string", "null"));
      property.put("x-display-only", true);
      property.put("x-ui", Map.of("x-list-visible",
          field == RecordSystemField.CREATED_AT || field == RecordSystemField.UPDATED_AT));
      if (field.type() == org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.DATETIME
          && !"MASKED".equals(permission)) {
        property.put("format", "date-time");
      }
      properties.put(field.property(), immutable(property));
      if (!"MASKED".equals(permission)) {
        queryFields.put(field.property(), queryField(FormFieldType.valueOf(field.type().name())));
      }
    }
    var defaults = form.document().presentation() == null ? List.<String>of() : form.document().presentation().listFieldOrder();
    var orderedFields = new ArrayList<>(document.fields());
    orderedFields.sort(java.util.Comparator.comparingInt(field -> {
      int index = defaults.indexOf(field.fieldId());
      return index < 0 ? Integer.MAX_VALUE : index;
    }));
    for (ApplicationFormRuntimeFieldDefinition field : orderedFields) {
      FormFieldDefinition storedField = storedFields.get(field.fieldId());
      if (storedField == null) {
        continue;
      }
      if (RECORD_ENVELOPE_FIELDS.contains(field.fieldId()) || RecordSystemField.properties().contains(field.fieldId())) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.DOCUMENT_INVALID,
            "Published form contains a field identity reserved by the record runtime"
        );
      }
      String permission = RecordPermissionModel.access(context.fieldPermissions(), form.physicalModelId(),
          storedField.fieldId(), storedField.storageCode());
      properties.put(
          field.fieldId(),
          fieldProperty(
              field,
              order++,
              permission != null && !"EDITABLE".equals(permission)
                  || !editableFields.contains(field.fieldId())
          )
      );
      if (!"MASKED".equals(permission)) {
        queryFields.put(field.fieldId(), queryField(field.type()));
      }
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", immutable(properties));
    schema.put("x-query", Map.of(
        "version", 1,
        "fields", immutable(queryFields),
        "relations", List.of(),
        "maxNodes", 32,
        "maxDepth", 4,
        "maxSort", 3,
        "maxPageSize", 100
    ));
    return Map.of(
        "schema", immutable(schema),
        "buttons", buttons(capabilities)
    );
  }

  private static Map<String, Object> fieldProperty(
      final ApplicationFormRuntimeFieldDefinition field,
      final int order,
      final boolean readOnly
  ) {
    Map<String, Object> property = new LinkedHashMap<>();
    property.put("type", List.of(jsonType(field.type()), "null"));
    property.put("title", field.label());
    property.put("x-order", order);
    property.put("x-ui", Map.of("x-list-visible", true));
    if (readOnly) {
      property.put("readOnly", true);
      property.put("x-display-only", true);
    }
    if (field.length() != null && TEXT_TYPES.contains(field.type())) {
      property.put("maxLength", field.length());
    }
    if (field.type() == FormFieldType.DECIMAL || field.type() == FormFieldType.INT64) {
      property.put("type", List.of("string", "null"));
      property.put("maxLength", 2000);
      property.put("x-decimal", exactNumberConstraints(field));
      property.put("x-ui", Map.of("x-list-visible", true, "widget", "Decimal"));
      if (field.type() == FormFieldType.INT64) {
        property.put("pattern", "^[+-]?[0-9]+$");
      }
    }
    if (field.type() == FormFieldType.JSON) {
      property.put("x-json-value", true);
      property.put("x-ui", Map.of("x-list-visible", true, "widget", "textarea", "format", "json"));
    }
    String format = format(field.type());
    if (format != null) {
      property.put("format", format);
    }
    if (!field.choices().isEmpty()) {
      property.put("type", List.of(switch (field.type()) {
        case BOOLEAN -> "boolean";
        case INT32 -> "integer";
        default -> "string";
      }, "null"));
      property.put("x-ui", Map.of("x-list-visible", true));
      property.remove("x-decimal");
      property.remove("pattern");
      var options = new java.util.ArrayList<Map<String, Object>>();
      field.choices().forEach(option -> options.add(choice(field.type(), option)));
      var empty = new LinkedHashMap<String, Object>();
      empty.put("const", null);
      empty.put("title", "");
      options.add(immutable(empty));
      property.put("oneOf", List.copyOf(options));
    }
    Object defaultValue = field.defaultValue() != null && field.defaultValue().kind() != FormValueKind.NULL
        && (field.type() == FormFieldType.DECIMAL || field.type() == FormFieldType.INT64)
        ? field.defaultValue().value() : defaultValue(field.defaultValue());
    if (defaultValue != null) {
      if (!field.choices().isEmpty()) {
        defaultValue = FormChoiceValues.wireValue(field.type(), field.defaultValue().value());
      }
      property.put("default", defaultValue);
    }
    return immutable(property);
  }

  private static Map<String, Object> exactNumberConstraints(final ApplicationFormRuntimeFieldDefinition field) {
    if (field.type() == FormFieldType.INT64) {
      return Map.of("scale", 0, "minimum", "-9223372036854775808", "maximum", "9223372036854775807");
    }
    BigDecimal limit = BigDecimal.ONE.scaleByPowerOfTen(field.precision() - field.scale())
        .subtract(BigDecimal.ONE.movePointLeft(field.scale()));
    return Map.of("precision", field.precision(), "scale", field.scale(),
        "minimum", limit.negate().toPlainString(), "maximum", limit.toPlainString());
  }

  private static Map<String, Object> systemProperty(
      final String title,
      final String type,
      final int order
  ) {
    return Map.of(
        "type", type,
        "title", title,
        "readOnly", true,
        "x-order", order,
        "x-ui", Map.of("x-list-visible", false)
    );
  }

  private static Map<String, Object> stateProperty(final int order) {
    return Map.of(
        "type", "string",
        "title", "i18n:app.runtime.record.state",
        "readOnly", true,
        "x-order", order,
        "x-ui", Map.of("x-list-visible", true),
        "oneOf", List.of(
            Map.of("const", "DRAFT", "title", "i18n:app.runtime.record.state.draft"),
            Map.of("const", "SUBMITTED", "title", "i18n:app.runtime.record.state.submitted")
        )
    );
  }

  private static Map<String, Object> queryField(final FormFieldType type) {
    List<String> operators = switch (type) {
      case STRING, TEXT -> TEXT_OPERATORS;
      case BOOLEAN, UUID -> EQUALITY_OPERATORS;
      case JSON -> NULL_OPERATORS;
      default -> COMPARABLE_OPERATORS;
    };
    return Map.of(
        "operators", operators,
        "sortable", type != FormFieldType.JSON,
        "javaType", javaType(type)
    );
  }

  private static List<Map<String, Object>> buttons(
      final ApplicationFormRuntimeCapabilities capabilities
  ) {
    List<Map<String, Object>> buttons = new ArrayList<>();
    // Resolving the published page already enforces its read permission and data scope.
    buttons.add(button("view", "i18n:app.runtime.record.detail", "EyeOutlined", 1, 1, 25, false));
    if (capabilities.canCreate()) {
      buttons.add(button("add", "i18n:table.button.add", "PlusOutlined", 0, 0, 0, false));
    }
    if (capabilities.canEdit()) {
      buttons.add(button("edit", "i18n:table.button.edit", "EditOutlined", 1, 1, 10, false));
    }
    if (capabilities.canDelete()) {
      buttons.add(button(
          "delete", "i18n:table.button.delete", "DeleteOutlined", 1, 1, 20, true
      ));
    }
    if (capabilities.canStartProcess() && capabilities.canEdit()) {
      buttons.add(button("startProcess", "i18n:app.submission.start", "SendOutlined", 1, 1, 30, false));
    }
    if (capabilities.canStartProcess()) {
      buttons.add(button("history", "i18n:app.runtime.approvalHistory", "HistoryOutlined", 0, 0, 40, false));
    }
    return List.copyOf(buttons);
  }

  private static Map<String, Object> button(
      final String key,
      final String title,
      final String icon,
      final int minimum,
      final int maximum,
      final int sort,
      final boolean danger
  ) {
    Map<String, Object> button = new LinkedHashMap<>();
    button.put("key", key);
    button.put("title", title);
    button.put("icon", icon);
    button.put("argumentMinSize", minimum);
    button.put("argumentMaxSize", maximum);
    button.put("sort", sort);
    button.put("danger", danger);
    if ("add".equals(key)) {
      button.put("type", "primary");
    }
    return immutable(button);
  }

  private static Map<String, Object> choice(
      final FormFieldType type, final FormChoiceOption option
  ) {
    return Map.of("const", FormChoiceValues.wireValue(type, option.value()), "title", option.label());
  }

  private static Object defaultValue(final FormValue value) {
    if (value == null || value.kind() == FormValueKind.NULL) {
      return null;
    }
    return switch (value.kind()) {
      case BOOLEAN -> Boolean.valueOf(value.value());
      case INTEGER -> Long.valueOf(value.value());
      case DECIMAL -> new BigDecimal(value.value());
      default -> value.value();
    };
  }

  private static String jsonType(final FormFieldType type) {
    return switch (type) {
      case BOOLEAN -> "boolean";
      case INT32, INT64 -> "integer";
      case DECIMAL -> "number";
      default -> "string";
    };
  }

  private static String javaType(final FormFieldType type) {
    return switch (type) {
      case BOOLEAN -> "Boolean";
      case INT32 -> "Integer";
      case INT64 -> "Long";
      case DECIMAL -> "BigDecimal";
      case DATE -> "LocalDate";
      case TIME -> "LocalTime";
      case DATETIME -> "Instant";
      default -> "String";
    };
  }

  private static String format(final FormFieldType type) {
    return switch (type) {
      case DATE -> "date";
      case TIME -> "time";
      case DATETIME -> "date-time";
      case UUID -> "uuid";
      default -> null;
    };
  }

  private static Set<String> editableFields(final ApplicationFormRuntimeDocument document) {
    Set<String> result = new java.util.LinkedHashSet<>();
    document.scenes().forEach(scene -> collectEditableFields(scene.root(), result));
    return Set.copyOf(result);
  }

  private static void collectEditableFields(
      final FormComponentDefinition component,
      final Set<String> fields
  ) {
    if (component.fieldId() != null && !component.readOnly()) {
      fields.add(component.fieldId());
    }
    component.children().forEach(child -> collectEditableFields(child, fields));
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.storageMode() == FormStorageMode.STORED
        || field.storageMode() == FormStorageMode.REFERENCE
        || field.storageMode() == FormStorageMode.ATTACHMENT;
  }

  private static Map<String, Object> immutable(final Map<String, Object> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
