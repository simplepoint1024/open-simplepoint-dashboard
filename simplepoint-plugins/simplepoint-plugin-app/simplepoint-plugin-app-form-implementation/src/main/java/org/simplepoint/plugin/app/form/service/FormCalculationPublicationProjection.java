package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRebindMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.springframework.stereotype.Component;

/** Compiles only immutable APP version facts into the neutral DNA publication contract. */
@Component
public class FormCalculationPublicationProjection {
  private final FormDocumentCodec documents;
  private final FormStorageProjectionCompiler storage;
  private final FormCalculationCompiler calculations;
  private final ObjectMapper mapper;

  /** No remote reads, draft reads, SQL or authorization snapshots enter this projection. */
  public FormCalculationPublicationProjection(final FormDocumentCodec documents, final FormStorageProjectionCompiler storage,
      final ObjectMapper mapper, final Clock clock) {
    this.documents = documents;
    this.storage = storage;
    this.mapper = mapper;
    this.calculations = new FormCalculationCompiler(clock);
  }

  /**
   * Absence preserves legacy publication; once installed, even removal carries an explicit
   * definition.
   */
  public RecordCalculationDefinition compile(
      final ApplicationFormVersionEntity version,
      final String parentModelId,
      final boolean previouslyInstalled) {
    return compile(version, parentModelId, previouslyInstalled, null);
  }

  CompiledPublication compilePublication(
      ApplicationFormVersionEntity version,
      String parentModelId,
      RecordCalculationDefinition previous,
      List<RecordValidationRule> pageRequirements) {
    var document = documents.decode(version.getDocumentJson());
    return new CompiledPublication(
        compile(version, document, parentModelId, previous != null, pageRequirements),
        FormRelationConversion.command(document, previous),
        FormCollectionRebinding.command(document, previous));
  }

  /**
   * Includes the observed surviving pages after the publication state service has rechecked their
   * epoch.
   */
  public RecordCalculationDefinition compile(
      final ApplicationFormVersionEntity version,
      final String parentModelId,
      final boolean previouslyInstalled,
      final List<RecordValidationRule> pageRequirements) {
    return compile(
        version,
        documents.decode(version.getDocumentJson()),
        parentModelId,
        previouslyInstalled,
        pageRequirements);
  }

  private RecordCalculationDefinition compile(
      ApplicationFormVersionEntity version,
      ApplicationFormDocument document,
      String parentModelId,
      boolean previouslyInstalled,
      List<RecordValidationRule> pageRequirements) {
    try {
      return compileVersion(
          version, document, parentModelId, previouslyInstalled, pageRequirements);
    } catch (IllegalArgumentException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID,
          "Calculation publication exceeds the supported definition contract",
          failure);
    }
  }

  private RecordCalculationDefinition compileVersion(
      final ApplicationFormVersionEntity version,
      final ApplicationFormDocument document,
      final String parentModelId,
      final boolean previouslyInstalled,
      final List<RecordValidationRule> pageRequirements) {
    if (!documents.encode(document).checksum().equals(version.getChecksum())) {
      throw invalid("Immutable form document checksum does not match");
    }
    var relationBindings =
        documents.decodeRelationBindings(document, version.getRelationBindingsJson());
    if (!previouslyInstalled
        && !FormCalculationCompiler.hasCalculations(document)
        && document.relations().isEmpty()) {
      return null;
    }
    calculations.compile(document);
    var storedSchema = storage.decode(version.getStorageSchemaJson());
    var parent = storage.compile(document, relationBindings, Boolean.TRUE.equals(storedSchema.tenantRecordIdentity()));
    if (version.getPhysicalModelVersion() == null || !parent.checksum().equals(version.getStorageChecksum())
        || !parent.schema().equals(storedSchema)) {
      throw invalid(
          "Calculation publication requires the matching immutable parent storage projection");
    }
    Map<String, FormDetailBinding> bindings = new LinkedHashMap<>();
    for (var binding : FormDetailSchemaCoordinator.decode(version.getDetailBindingsJson(), mapper)) {
      if (binding == null || bindings.putIfAbsent(binding.detailId(), binding) != null) {
        throw invalid("Immutable detail schema bindings are ambiguous");
      }
    }
    if (!bindings.keySet().equals(document.details().stream().map(detail -> detail.detailId()).collect(java.util.stream.Collectors.toSet()))) {
      throw invalid("Immutable detail schema bindings are incomplete");
    }
    Map<String, RecordCalculationScope> details = new LinkedHashMap<>();
    for (var detail : document.details()) {
      var binding = bindings.get(detail.detailId());
      if (parentModelId.equals(binding.physicalModelId())
          || !storage.compileDetail(document, detail, parentModelId, relationBindings).checksum().equals(binding.checksum())) {
        throw invalid("Detail binding does not match the immutable owned storage projection");
      }
      details.put(
          detail.detailId(),
          new RecordCalculationScope(
              binding.physicalModelId(),
              new RecordSchemaReference(binding.schemaVersion(), binding.checksum()),
              formulas(detail.fields()),
              FormCalculationFieldConstraints.compile(detail.fields())));
    }
    var effective =
        FormRelationConversion.retiredFields(document).isEmpty()
            ? document
            : FormWritePolicyProjection.apply(document, document);
    var validations =
        FormCalculationValidationProjection.merge(
            FormCalculationValidationProjection.compile(effective),
            pageRequirements == null ? List.of() : pageRequirements);
    var parentConstraints = FormCalculationFieldConstraints.compile(document.fields());
    // Both state membership and transitions remain enforced after the last formula is removed.
    parentConstraints.put(FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID, new RecordFieldConstraints(null, null, null,
        java.util.Arrays.stream(FormRecordState.values()).map(state -> new LogicalValue(LogicalValueKind.STRING, state.name())).toList()));
    var states =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordStateTransitions(
            FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID,
            List.of("DRAFT", "SUBMITTED"),
            Map.of("DRAFT", List.of("DRAFT", "SUBMITTED"), "SUBMITTED", List.of("SUBMITTED")));
    return new RecordCalculationDefinition(
        "1",
        formulas(document.fields()),
        details,
        parentConstraints,
        validations,
        states,
        FormCalculationWriteProjection.compile(document),
        FormRelationPublicationProjection.rules(document, relationBindings),
        FormRelationPublicationProjection.collections(
            document,
            relationBindings,
            FormCollectionSchemaBinding.decode(version.getCollectionBindingsJson(), mapper),
            parentModelId,
            storage));
  }

  record CompiledPublication(
      RecordCalculationDefinition definition,
      RecordReferenceCollectionMigration conversion,
      RecordCollectionRebindMigration rebind) {}

  private static Map<String, String> formulas(final List<FormFieldDefinition> fields) {
    Map<String, String> formulas = new LinkedHashMap<>();
    fields.stream().filter(field -> field.calculation() != null)
        .forEach(field -> formulas.put(field.fieldId(), field.calculation().expression()));
    return formulas;
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, message);
  }
}
