package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Server-owned observation identity; never hashes record values or an authorization snapshot. */
record RecordReferenceMappingBinding(String revision, RecordReferenceCopyPlan copies) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final PhysicalSchemaCodec SCHEMAS = new PhysicalSchemaCodec(JSON);

  static RecordReferenceMappingBinding bind(RecordReferenceAdmission.Plan plan, String relationId) {
    var owner = source(plan, relationId);
    var binding = plan.bindings().get(relationId);
    var rule = binding.rule();
    var source = owner.model();
    var target = binding.target();
    var policy = plan.policy().policy();
    if (target == null
        || !rule.targetResourceCode().equals(target.access().resourceCode())
        || target.access().resourceGeneration() < 1
        || target.snapshot().revision() < 0) {
      throw RecordReferenceAdmission.incompatible();
    }
    RecordReferenceRules.requireTargetIdentity(rule, source, target.model());
    var targetPolicy = target.snapshot().calculation();
    if (targetPolicy != null
        && (!target.model().appId().equals(targetPolicy.policy().appId())
            || !target.model().formId().equals(targetPolicy.policy().formId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
    var fields =
        rule.mappings().stream()
            .map(
                mapping ->
                    new RecordReferenceCopyPlan.BoundField(
                        mapping,
                        field(target.model(), mapping.targetFieldId()),
                        field(source, mapping.destinationFieldId())))
            .toList();
    var identity =
        new Identity(
            "reference-mapping:1",
            target.access().actor().tenantId(),
            relationId,
            source.physicalModelId(),
            plan.policy().revision(),
            RecordCalculationStore.policyChecksum(policy),
            source.activeVersion(),
            SCHEMAS.encode(source.activeSchema()).checksum(),
            source.target(),
            owner.parent().target(),
            rule.targetModelId(),
            rule.targetAppId(),
            rule.targetFormId(),
            rule.targetResourceCode(),
            target.access().resourceGeneration(),
            target.model().activeVersion(),
            SCHEMAS.encode(target.model().activeSchema()).checksum(),
            target.model().target(),
            target.snapshot().revision(),
            targetPolicy == null ? 0 : targetPolicy.revision(),
            targetPolicy == null
                ? null
                : RecordCalculationStore.policyChecksum(targetPolicy.policy()));
    String revision = digest(identity);
    return new RecordReferenceMappingBinding(
        revision, new RecordReferenceCopyPlan(revision, fields));
  }

  static Source source(RecordReferenceAdmission.Plan plan, String relationId) {
    var binding = plan.bindings().get(relationId);
    if (binding == null || plan.policy() == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    var rule = binding.rule();
    var source = plan.sources().get(binding.sourceModelId());
    var policy = plan.policy().policy();
    if (source == null
        || rule.mappings().isEmpty()
        || !policy.definition().referencesOrEmpty().contains(rule)
        || !policy.appId().equals(source.appId())
        || source.activeSchema().references().stream()
            .noneMatch(
                reference ->
                    reference.relationId().equals(relationId)
                        && reference.fieldId().equals(rule.source().fieldId())
                        && reference.targetModelId().equals(rule.targetModelId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
    RuntimeRecordModel parent;
    if (rule.source().scope().equals("parent")) {
      parent = source;
      if (source.activeSchema().owner() != null) {
        throw RecordReferenceAdmission.incompatible();
      }
    } else {
      var detail = policy.definition().details().get(rule.source().scope());
      var ownership = source.activeSchema().owner();
      if (detail == null
          || ownership == null
          || !detail.physicalModelId().equals(source.physicalModelId())) {
        throw RecordReferenceAdmission.incompatible();
      }
      parent = plan.sources().get(ownership.parentModelId());
      if (parent == null) {
        throw RecordReferenceAdmission.incompatible();
      }
      OwnedRecordBinding.parentCode(parent, source);
    }
    if (parent.activeSchema().owner() != null
        || !policy.appId().equals(parent.appId())
        || !policy.formId().equals(parent.formId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    return new Source(
        rule, source, parent, plan.policy(), SCHEMAS.encode(source.activeSchema()).checksum());
  }

  static String digest(Object identity) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(identity)));
    } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  record Source(
      org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule rule,
      RuntimeRecordModel model,
      RuntimeRecordModel parent,
      RecordCalculationStore.Installed installed,
      String schemaChecksum) {}

  RecordReferenceMappingObservation observe(RecordView target) {
    return new RecordReferenceMappingObservation(
        revision, target == null ? null : target.id(), target == null ? null : target.version());
  }

  static LogicalFieldDefinition field(RuntimeRecordModel model, String id) {
    // An installed mapping always resolves against the active storage schema. The request's
    // historical column names cannot choose another target or destination field.
    List<LogicalFieldDefinition> matches =
        model.activeSchema().fields().stream().filter(field -> field.fieldId().equals(id)).toList();
    if (matches.size() != 1) {
      throw RecordReferenceAdmission.incompatible();
    }
    return matches.getFirst();
  }

  private record Identity(
      String domain,
      String tenantId,
      String relationId,
      String sourceModelId,
      long sourceRevision,
      String sourcePolicyChecksum,
      int sourceSchemaVersion,
      String sourceSchemaChecksum,
      PhysicalSchemaTarget sourceTarget,
      PhysicalSchemaTarget aggregateTarget,
      String targetModelId,
      String targetAppId,
      String targetFormId,
      String targetResourceCode,
      long resourceGeneration,
      int targetSchemaVersion,
      String targetSchemaChecksum,
      PhysicalSchemaTarget target,
      long targetFenceRevision,
      long targetCalculationRevision,
      String targetPolicyChecksum) {}
}
