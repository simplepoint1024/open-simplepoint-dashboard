package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Compiles one validated APP data schema into a deterministic DNA storage projection. */
public class FormStorageProjectionCompiler {

  static final String RECORD_STATE_FIELD_ID = "platform_record_state";
  static final String RECORD_STATE_CODE = "record_state";

  private final ObjectMapper mapper;

  /** Creates an isolated canonical mapper without changing the platform mapper. */
  public FormStorageProjectionCompiler(final ObjectMapper mapper) {
    this.mapper = mapper.copy()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }

  /** Compiles stable fields and indexes while excluding UI-only definitions. */
  public CompiledStorageProjection compile(final ApplicationFormDocument document) {
    List<FormFieldDefinition> persistentFields = document.fields().stream()
        .filter(FormStorageProjectionCompiler::persistent)
        .sorted(Comparator.comparing(FormFieldDefinition::fieldId))
        .toList();
    Map<String, String> fieldCodes = new LinkedHashMap<>();
    List<LogicalFieldDefinition> fields = new ArrayList<>();
    fields.add(recordStateField());
    fields.addAll(persistentFields.stream()
        .map(field -> {
          fieldCodes.put(field.fieldId(), field.storageCode());
          return compileField(field);
        })
        .toList());
    List<LogicalIndexDefinition> indexes = document.indexes().stream()
        .sorted(Comparator.comparing(FormIndexDefinition::indexId))
        .map(index -> new LogicalIndexDefinition(
            index.storageCode(),
            index.fieldIds().stream().map(fieldCodes::get).toList(),
            index.unique()
        ))
        .toList();
    PhysicalSchemaDefinition schema = new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        fields,
        indexes
    );
    return encode(schema, fieldCodes);
  }

  /** Compiles an owned table with a server controlled parent reference and row ordering. */
  public CompiledStorageProjection compileDetail(
      final org.simplepoint.plugin.app.form.api.model.FormDetailDefinition detail,
      final String parentModelId
  ) {
    Map<String, String> codes = new LinkedHashMap<>();
    List<LogicalFieldDefinition> fields = new ArrayList<>();
    fields.add(new LogicalFieldDefinition("owned_parent", "parent_record_id", "Parent record",
        LogicalFieldType.UUID, false, null, null, null, null));
    fields.add(new LogicalFieldDefinition("owned_order", "row_order", "Row order",
        LogicalFieldType.INT32, false, null, null, null, null));
    detail.fields().stream().sorted(Comparator.comparing(FormFieldDefinition::fieldId)).forEach(field -> {
      codes.put(field.fieldId(), field.storageCode());
      fields.add(compileField(field));
    });
    List<LogicalIndexDefinition> indexes = new ArrayList<>();
    indexes.add(new LogicalIndexDefinition("owned_parent_order", List.of("parent_record_id", "row_order"), false));
    detail.indexes().stream().sorted(Comparator.comparing(FormIndexDefinition::indexId)).forEach(index -> {
      List<String> columns = new ArrayList<>();
      columns.add("parent_record_id");
      columns.addAll(index.fieldIds().stream().map(codes::get).toList());
      indexes.add(new LogicalIndexDefinition(index.storageCode(), columns, index.unique()));
    });
    return encode(new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, fields, indexes,
        new org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation(parentModelId, "owned_parent")), codes);
  }

  private CompiledStorageProjection encode(final PhysicalSchemaDefinition schema, final Map<String, String> fieldCodes) {
    try {
      String json = mapper.writeValueAsString(schema);
      return new CompiledStorageProjection(
          schema,
          json,
          checksum(json.getBytes(StandardCharsets.UTF_8)),
          Map.copyOf(fieldCodes)
      );
    } catch (JsonProcessingException exception) {
      throw failure("Form storage projection cannot be serialized", exception);
    }
  }

  /** Restores one persisted DNA storage projection and reapplies contract validation. */
  public PhysicalSchemaDefinition decode(final String json) {
    try {
      return mapper.readValue(json, PhysicalSchemaDefinition.class);
    } catch (JsonProcessingException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED,
          "Stored form storage projection is invalid",
          exception
      );
    }
  }

  private LogicalFieldDefinition compileField(final FormFieldDefinition field) {
    return new LogicalFieldDefinition(
        field.fieldId(),
        field.storageCode(),
        field.storageLabel(),
        LogicalFieldType.valueOf(field.type().name()),
        !field.storageRequired(),
        field.length(),
        field.precision(),
        field.scale(),
        compileValue(field, field.defaultValue())
    );
  }

  private static LogicalFieldDefinition recordStateField() {
    return new LogicalFieldDefinition(
        RECORD_STATE_FIELD_ID,
        RECORD_STATE_CODE,
        "记录状态",
        LogicalFieldType.STRING,
        false,
        16,
        null,
        null,
        new LogicalValue(LogicalValueKind.STRING, "DRAFT")
    );
  }

  private LogicalValue compileValue(final FormFieldDefinition field, final FormValue value) {
    if (value == null) {
      return null;
    }
    if (value.value() == null) {
      return LogicalValue.nullValue();
    }
    String canonical = switch (field.type()) {
      case STRING, TEXT -> value.value();
      case BOOLEAN -> Boolean.toString(Boolean.parseBoolean(value.value()));
      case INT32 -> Integer.toString(Integer.parseInt(value.value()));
      case INT64 -> Long.toString(Long.parseLong(value.value()));
      case DECIMAL -> new BigDecimal(value.value()).setScale(field.scale(), java.math.RoundingMode.UNNECESSARY)
          .toPlainString();
      case DATE -> LocalDate.parse(value.value()).toString();
      case TIME -> LocalTime.parse(value.value()).toString();
      case DATETIME -> Instant.parse(value.value()).toString();
      case UUID -> UUID.fromString(value.value()).toString();
      case JSON -> canonicalJson(value.value());
    };
    return new LogicalValue(LogicalValueKind.valueOf(value.kind().name()), canonical);
  }

  private String canonicalJson(final String value) {
    try {
      return mapper.writeValueAsString(normalizeJson(mapper.readValue(value, Object.class)));
    } catch (JsonProcessingException exception) {
      throw failure("Form JSON default cannot be canonicalized", exception);
    }
  }

  private static Object normalizeJson(final Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new TreeMap<>();
      map.forEach((key, child) -> normalized.put(key.toString(), normalizeJson(child)));
      return normalized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(FormStorageProjectionCompiler::normalizeJson).toList();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros();
    }
    return value;
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.storageMode() == FormStorageMode.STORED
        || field.storageMode() == FormStorageMode.REFERENCE
        || field.storageMode() == FormStorageMode.ATTACHMENT;
  }

  private static String checksum(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static ApplicationFormException failure(
      final String message,
      final Throwable cause
  ) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message, cause);
  }

  /** Immutable compiled projection stored with a form version. */
  public record CompiledStorageProjection(
      PhysicalSchemaDefinition schema,
      String json,
      String checksum,
      Map<String, String> fieldCodes
  ) {

    /** Keeps the field identity map immutable. */
    public CompiledStorageProjection {
      fieldCodes = Map.copyOf(fieldCodes);
    }
  }
}
