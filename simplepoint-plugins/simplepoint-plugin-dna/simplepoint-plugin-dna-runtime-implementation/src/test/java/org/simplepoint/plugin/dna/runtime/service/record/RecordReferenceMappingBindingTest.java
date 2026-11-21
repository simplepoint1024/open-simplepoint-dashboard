package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordReferenceMappingBindingTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";

  @Test
  void observationIsBoundToPolicySchemaGenerationFenceAndTenantButNotUserValues() {
    var original = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var binding = original.mapping("customer");
    assertThat(binding.revision()).matches("[a-f0-9]{64}");
    assertThat(binding.observe(new RecordView(ROW, 7, Map.of("target_name", "客户"))))
        .isEqualTo(binding.observe(new RecordView(ROW, 7, Map.of("target_name", "日本語"))));
    assertThat(binding.observe(null).targetRecordId()).isNull();
    assertThat(binding.observe(new RecordView(ROW, 8, Map.of())).targetVersion()).isEqualTo(8);
    assertThat(
            fixture("tenant", "another-actor", 3, 4, 5, "target_name", 0)
                .mapping("customer")
                .revision())
        .isEqualTo(binding.revision());
    for (var changed :
        List.of(
            fixture("other-tenant", "actor", 3, 4, 5, "target_name", 0),
            fixture("tenant", "actor", 4, 4, 5, "target_name", 0),
            fixture("tenant", "actor", 3, 5, 5, "target_name", 0),
            fixture("tenant", "actor", 3, 4, 6, "target_name", 0),
            fixture("tenant", "actor", 3, 4, 5, "renamed_name", 0),
            fixture("tenant", "actor", 3, 4, 5, "target_name", 1))) {
      assertThat(changed.mapping("customer").revision()).isNotEqualTo(binding.revision());
    }
  }

  @Test
  void activeStableIdentitiesResolveRenamedColumnsAndRejectOldConfirmation() {
    var old = fixture("tenant", "actor", 3, 4, 5, "target_name", 0).mapping("customer");
    var current = fixture("tenant", "actor", 3, 4, 5, "renamed_name", 0).mapping("customer");
    var target = new RecordView(ROW, 7, Map.of("renamed_name", "客户😀"));
    assertThatThrownBy(
            () ->
                current
                    .copies()
                    .prepare(
                        ROW,
                        target,
                        old.observe(target),
                        Set.of("renamed_name"),
                        Set.of(),
                        Set.of("copied_name"),
                        Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var copied =
        current
            .copies()
            .prepare(
                ROW,
                target,
                current.observe(target),
                Set.of("renamed_name"),
                Set.of(),
                Set.of("copied_name"),
                Set.of());
    assertThat(copied.managed().get("copy").canonicalValue()).isEqualTo("客户😀");
  }

  @Test
  void physicalBindingChangeInvalidatesConfirmationWithoutChangingTheLogicalFieldIdentity() {
    var original = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var source = original.sources().get("source");
    var moved =
        new RuntimeRecordModel(
            source.physicalModelId(),
            source.appId(),
            source.formId(),
            source.activeVersion(),
            source.activeSchema(),
            new PhysicalSchemaTarget(
                "source",
                new RuntimeMetadataTarget("db", "catalog", "schema", "moved_source"),
                source.target().referenceTargets()));
    var relocated =
        new RecordReferenceAdmission.Plan(
            original.policy(), Map.of("source", moved), original.bindings());
    assertThat(relocated.mapping("customer").revision())
        .isNotEqualTo(original.mapping("customer").revision());
  }

  @Test
  void unavailableOrForeignResourceCannotProduceConfirmation() {
    var plan = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    assertThatThrownBy(() -> plan.mapping("unknown")).isInstanceOf(DnaRuntimeException.class);
    var source = plan.bindings().get("customer");
    var unavailable =
        new RecordReferenceAdmission.Plan(
            plan.policy(),
            plan.sources(),
            Map.of(
                "customer", new RecordReferenceAdmission.Binding(source.rule(), "source", null)));
    assertThatThrownBy(() -> unavailable.mapping("customer"))
        .isInstanceOf(DnaRuntimeException.class);
    var access = source.target().access();
    var foreign =
        new RecordAccessContext(
            access.actor(),
            "another-page",
            access.readableFields(),
            access.writableFields(),
            access.maskedFields(),
            access.rowScope(),
            null,
            4);
    var wrongResource =
        new RecordReferenceAdmission.Plan(
            plan.policy(),
            plan.sources(),
            Map.of(
                "customer",
                new RecordReferenceAdmission.Binding(
                    source.rule(),
                    "source",
                    new RecordReferenceAdmission.Target(
                        source.target().model(), foreign, source.target().snapshot()))));
    assertThatThrownBy(() -> wrongResource.mapping("customer"))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> fixture("tenant", "actor", 3, 0, 5, "target_name", 0).mapping("customer"))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void clearingRemainsAvailableWithoutTargetReadPermissionAndHasItsOwnConfirmationDomain() {
    var plan = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var binding = plan.bindings().get("customer");
    var unavailable =
        new RecordReferenceAdmission.Plan(
            plan.policy(),
            plan.sources(),
            Map.of(
                "customer", new RecordReferenceAdmission.Binding(binding.rule(), "source", null)));
    var actor = new RuntimeActor("tenant", "actor");
    var clear = RecordReferenceMappingClear.bind(unavailable, "customer", actor);
    assertThat(clear.observe())
        .isEqualTo(RecordReferenceMappingClear.bind(plan, "customer", actor).observe());
    assertThat(clear.revision()).isNotEqualTo(plan.mapping("customer").revision());
    assertThat(clear.prepare(clear.observe(), Set.of("copied_name"), Set.of()).managed())
        .containsEntry(
            "copy", org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue.nullValue());
    assertThatThrownBy(
            () ->
                clear.prepare(
                    plan.mapping("customer").observe(null), Set.of("copied_name"), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void clearingCannotBypassDestinationGrantsTenantOrChangedSourcePolicy() {
    var plan = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var clear =
        RecordReferenceMappingClear.bind(plan, "customer", new RuntimeActor("tenant", "actor"));
    assertThatThrownBy(() -> clear.prepare(clear.observe(), Set.of(), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var other =
        RecordReferenceMappingClear.bind(plan, "customer", new RuntimeActor("other", "actor"));
    assertThatThrownBy(() -> clear.prepare(other.observe(), Set.of("copied_name"), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var changed =
        RecordReferenceMappingClear.bind(
            fixture("tenant", "actor", 4, 4, 5, "target_name", 0),
            "customer",
            new RuntimeActor("tenant", "actor"));
    assertThatThrownBy(() -> changed.prepare(clear.observe(), Set.of("copied_name"), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> clear.prepare(clear.observe(), Set.of("copied_name"), Set.of("copy-name")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void clearingHonorsRejectAndPreservePoliciesWithoutInventingTargetMetadata() {
    var plan = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var actor = new RuntimeActor("tenant", "actor");
    var reject =
        RecordReferenceMappingClear.bind(
            withMapping(
                plan,
                RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
                RecordReferenceMapping.NullPolicy.REJECT),
            "customer",
            actor);
    assertThatThrownBy(() -> reject.prepare(reject.observe(), Set.of("copied_name"), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var preserve =
        RecordReferenceMappingClear.bind(
            withMapping(
                plan,
                RecordReferenceMapping.Mode.EDITABLE_DEFAULT,
                RecordReferenceMapping.NullPolicy.PRESERVE),
            "customer",
            actor);
    assertThat(preserve.prepare(preserve.observe(), Set.of(), Set.of()).suggestions()).isEmpty();
    assertThat(
            preserve
                .prepare(preserve.observe(), Set.of("copied_name"), Set.of("copy-name"))
                .suggestions())
        .isEmpty();
    assertThatThrownBy(() -> preserve.prepare(preserve.observe(), Set.of(), Set.of("copy-name")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void ownedMappingUsesItsDeclaredRootWithDifferentDetailFormIdentity() {
    var original = fixture("tenant", "actor", 3, 4, 5, "target_name", 0);
    var source = original.sources().get("source");
    var rootSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(field("root", "root_name", LogicalFieldType.STRING)),
            List.of());
    var root =
        new RuntimeRecordModel(
            "source", "source-app", "source-form", 1, rootSchema, source.target());
    var fields = new java.util.ArrayList<>(source.activeSchema().fields());
    fields.add(
        new LogicalFieldDefinition(
            "owner",
            "parent_record_id",
            "Owner",
            LogicalFieldType.UUID,
            false,
            null,
            null,
            null,
            null));
    var childSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            fields,
            List.of(),
            new org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation("source", "owner"),
            true,
            source.activeSchema().references());
    var child =
        new RuntimeRecordModel(
            "detail",
            "source-app",
            "detail-form",
            1,
            childSchema,
            new PhysicalSchemaTarget(
                "detail",
                new RuntimeMetadataTarget("db", "catalog", "schema", "detail"),
                source.target().referenceTargets()));
    var prior = original.bindings().get("customer");
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("lines", "customer"),
            "target",
            "target-app",
            "target-form",
            "target-page",
            null,
            null,
            null,
            prior.rule().mappings());
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of(),
            Map.of(
                "lines",
                new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope(
                    "detail", new RecordSchemaReference(1, "c".repeat(64)), Map.of())),
            null,
            null,
            null,
            null,
            List.of(rule));
    var policy =
        new RecordCalculationStore.Installed(
            3,
            new RecordCalculationPolicy(
                1,
                "source-app",
                "source-form",
                original.policy().policy().parentSchema(),
                definition));
    var bindings =
        Map.of("customer", new RecordReferenceAdmission.Binding(rule, "detail", prior.target()));
    var plan =
        new RecordReferenceAdmission.Plan(
            policy, Map.of("source", root, "detail", child), bindings);
    assertThat(plan.mapping("customer").revision()).matches("[a-f0-9]{64}");
    assertThat(
            RecordReferenceMappingClear.bind(plan, "customer", new RuntimeActor("tenant", "actor"))
                .prepare(
                    RecordReferenceMappingClear.bind(
                            plan, "customer", new RuntimeActor("tenant", "actor"))
                        .observe(),
                    Set.of("copied_name"),
                    Set.of())
                .managed())
        .containsKey("copy");
    var missingRoot = new RecordReferenceAdmission.Plan(policy, Map.of("detail", child), bindings);
    assertThatThrownBy(() -> missingRoot.mapping("customer"))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceAdmission.Plan withMapping(
      RecordReferenceAdmission.Plan plan,
      RecordReferenceMapping.Mode mode,
      RecordReferenceMapping.NullPolicy nullPolicy) {
    var old = plan.bindings().get("customer");
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            old.rule().source(),
            "target",
            "target-app",
            "target-form",
            "target-page",
            null,
            null,
            null,
            List.of(
                new RecordReferenceMapping(
                    "copy-name",
                    "name",
                    "copy",
                    mode,
                    RecordReferenceConversion.IDENTITY,
                    nullPolicy)));
    var policy = plan.policy().policy();
    var installed =
        new RecordCalculationStore.Installed(
            plan.policy().revision(),
            new RecordCalculationPolicy(
                1,
                policy.appId(),
                policy.formId(),
                policy.parentSchema(),
                new RecordCalculationDefinition(
                    "1", Map.of(), Map.of(), null, null, null, null, List.of(rule))));
    return new RecordReferenceAdmission.Plan(
        installed,
        plan.sources(),
        Map.of("customer", new RecordReferenceAdmission.Binding(rule, "source", null)));
  }

  private static RecordReferenceAdmission.Plan fixture(
      String tenant,
      String actor,
      long sourceRevision,
      long generation,
      long fence,
      String targetCode,
      long targetRevision) {
    var targetLocation = new RuntimeMetadataTarget("db", "catalog", "schema", "customer");
    var targetSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(field("name", targetCode, LogicalFieldType.STRING)),
            List.of(),
            null,
            true);
    var target =
        new RuntimeRecordModel(
            "target",
            "target-app",
            "target-form",
            1,
            targetSchema,
            new PhysicalSchemaTarget("target", targetLocation));
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                field("customer", "customer_id", LogicalFieldType.UUID),
                field("copy", "copied_name", LogicalFieldType.STRING)),
            List.of(),
            null,
            true,
            List.of(new LogicalReferenceDefinition("customer", "customer", "target", false)));
    var source =
        new RuntimeRecordModel(
            "source",
            "source-app",
            "source-form",
            1,
            schema,
            new PhysicalSchemaTarget(
                "source",
                new RuntimeMetadataTarget("db", "catalog", "schema", "source"),
                Map.of("target", targetLocation)));
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "customer"),
            "target",
            "target-app",
            "target-form",
            "target-page",
            null,
            null,
            null,
            List.of(
                new RecordReferenceMapping(
                    "copy-name",
                    "name",
                    "copy",
                    RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
                    RecordReferenceConversion.IDENTITY,
                    RecordReferenceMapping.NullPolicy.CLEAR)));
    var policy =
        new RecordCalculationPolicy(
            1,
            "source-app",
            "source-form",
            new RecordSchemaReference(1, "a".repeat(64)),
            new RecordCalculationDefinition(
                "1", Map.of(), Map.of(), null, null, null, null, List.of(rule)));
    var targetPolicy =
        targetRevision == 0
            ? null
            : new RecordCalculationStore.Installed(
                targetRevision,
                new RecordCalculationPolicy(
                    1,
                    "target-app",
                    "target-form",
                    new RecordSchemaReference(1, "b".repeat(64)),
                    new RecordCalculationDefinition("1", Map.of(), Map.of())));
    var access =
        new RecordAccessContext(
            new RuntimeActor(tenant, actor),
            "target-page",
            Set.of("id", targetCode),
            Set.of(),
            Set.of(),
            null,
            null,
            generation);
    return new RecordReferenceAdmission.Plan(
        new RecordCalculationStore.Installed(sourceRevision, policy),
        Map.of("source", source),
        Map.of(
            "customer",
            new RecordReferenceAdmission.Binding(
                rule,
                "source",
                new RecordReferenceAdmission.Target(
                    target,
                    access,
                    new RecordReferenceAdmission.TargetSnapshot(fence, targetPolicy)))));
  }

  private static LogicalFieldDefinition field(String id, String code, LogicalFieldType type) {
    return new LogicalFieldDefinition(
        id, code, code, type, true, type == LogicalFieldType.STRING ? 100 : null, null, null, null);
  }
}
