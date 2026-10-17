package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormValue;

/** Canonical serializer and checksum boundary for immutable form documents. */
public class FormDocumentCodec {

  private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

  private final ObjectMapper mapper;

  /** Creates a private deterministic mapper without mutating the platform mapper. */
  public FormDocumentCodec(final ObjectMapper mapper) {
    this.mapper =
        mapper
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }

  /** Encodes one form document and enforces the persisted size budget. */
  public EncodedDocument encode(final ApplicationFormDocument document) {
    try {
      String json = mapper.writeValueAsString(document);
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_DOCUMENT_BYTES) {
        throw invalid("Form document exceeds the 2 MiB storage budget", null);
      }
      byte[] canonicalBytes = mapper.writeValueAsBytes(canonicalDocument(document));
      return new EncodedDocument(json, checksum(canonicalBytes));
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw invalid("Form document cannot be serialized", exception);
    }
  }

  private ApplicationFormDocument canonicalDocument(final ApplicationFormDocument document)
      throws JsonProcessingException {
    return new ApplicationFormDocument(
        document.contractVersion(),
        document.fields().stream()
            .map(this::canonicalField)
            .sorted(Comparator.comparing(FormFieldDefinition::fieldId))
            .toList(),
        document.indexes().stream()
            .sorted(Comparator.comparing(FormIndexDefinition::indexId))
            .toList(),
        document.scenes().stream().map(this::canonicalScene).toList(),
        document.rules().stream().map(this::canonicalRule).toList(),
        document.details().stream()
            .sorted(
                Comparator.comparing(
                    org.simplepoint.plugin.app.form.api.model.FormDetailDefinition::detailId))
            .map(
                detail ->
                    new org.simplepoint.plugin.app.form.api.model.FormDetailDefinition(
                        detail.detailId(),
                        detail.storageCode(),
                        detail.label(),
                        detail.fields().stream()
                            .map(this::canonicalField)
                            .sorted(Comparator.comparing(FormFieldDefinition::fieldId))
                            .toList(),
                        detail.indexes().stream()
                            .sorted(Comparator.comparing(FormIndexDefinition::indexId))
                            .toList()))
            .toList(), document.constraints(), document.presentation());
  }

  private FormFieldDefinition canonicalField(final FormFieldDefinition field) {
    return new FormFieldDefinition(
        field.fieldId(),
        field.storageCode(),
        field.label(),
        field.storageLabel(),
        field.type(),
        field.storageMode(),
        field.submitRequired(),
        field.storageRequired(),
        field.length(),
        field.precision(),
        field.scale(),
        canonicalValue(field.defaultValue()),
        field.choices(),
        field.writePolicy(), field.validation(), field.access());
  }

  private FormSceneDefinition canonicalScene(final FormSceneDefinition scene) {
    return new FormSceneDefinition(
        scene.sceneId(),
        scene.code(),
        scene.name(),
        scene.type(),
        scene.width(),
        canonicalComponent(scene.root()));
  }

  private FormComponentDefinition canonicalComponent(final FormComponentDefinition component) {
    Map<String, FormValue> properties = new TreeMap<>();
    component.properties().forEach((key, value) -> properties.put(key, canonicalValue(value)));
    return new FormComponentDefinition(
        component.componentId(),
        component.componentType(),
        component.componentVersion(),
        component.fieldId(),
        component.columnSpan(),
        component.label(),
        component.description(),
        component.placeholder(),
        component.targetSceneId(),
        component.readOnly(),
        properties,
        component.children().stream().map(this::canonicalComponent).toList());
  }

  private FormRuleDefinition canonicalRule(final FormRuleDefinition rule) {
    return new FormRuleDefinition(
        rule.ruleId(),
        rule.targetComponentId(),
        rule.effect(),
        canonicalCondition(rule.condition()));
  }

  private FormCondition canonicalCondition(final FormCondition condition) {
    return new FormCondition(
        condition.operator(),
        condition.fieldId(),
        condition.values().stream().map(this::canonicalValue).toList(),
        condition.children().stream().map(this::canonicalCondition).toList());
  }

  private FormValue canonicalValue(final FormValue value) {
    if (value == null || value.value() == null) {
      return value;
    }
    String canonical =
        switch (value.kind()) {
          case STRING -> value.value();
          case BOOLEAN -> Boolean.toString(Boolean.parseBoolean(value.value()));
          case INTEGER -> new BigInteger(value.value()).toString();
          case DECIMAL -> new BigDecimal(value.value()).stripTrailingZeros().toPlainString();
          case DATE -> LocalDate.parse(value.value()).toString();
          case TIME -> LocalTime.parse(value.value()).toString();
          case DATETIME -> Instant.parse(value.value()).toString();
          case UUID -> UUID.fromString(value.value()).toString();
          case JSON -> canonicalJson(value.value());
          case NULL -> throw new IllegalStateException("NULL form value cannot carry text");
        };
    return new FormValue(value.kind(), canonical);
  }

  private String canonicalJson(final String value) {
    try {
      return mapper.writeValueAsString(normalizeJson(mapper.readValue(value, Object.class)));
    } catch (JsonProcessingException exception) {
      throw invalid("Form JSON value cannot be canonicalized", exception);
    }
  }

  private static Object normalizeJson(final Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new TreeMap<>();
      map.forEach((key, child) -> normalized.put(key.toString(), normalizeJson(child)));
      return normalized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(FormDocumentCodec::normalizeJson).toList();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros();
    }
    return value;
  }

  /** Decodes optional persisted child model bindings with the private platform mapper. */
  public List<FormDetailBinding> decodeDetailBindings(final String json) {
    return FormDetailSchemaCoordinator.decode(json, mapper);
  }

  /** Restores one validated contract snapshot from persistence. */
  public ApplicationFormDocument decode(final String json) {
    try {
      return mapper.readValue(json, ApplicationFormDocument.class);
    } catch (JsonProcessingException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED,
          "Stored form document is invalid",
          exception);
    }
  }

  private static String checksum(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static ApplicationFormException invalid(final String message, final Throwable cause) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message, cause);
  }

  /** Persisted document text and its normalized semantic checksum. */
  public record EncodedDocument(String json, String checksum) {}
}
