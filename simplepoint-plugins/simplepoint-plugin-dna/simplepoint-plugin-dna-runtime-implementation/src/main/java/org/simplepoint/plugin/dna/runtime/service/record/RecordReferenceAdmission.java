package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Resolves bounded target participants before entering the source's physical transaction. */
@Component
public class RecordReferenceAdmission {
  private final RuntimeRequestContext context;
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy permissions;
  private final RecordCalculationAccess calculations;
  private final RecordSqlCompiler compiler;
  private final org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec schemas;

  /** Resolves trusted participants independently of the owning physical write transaction. */
  public RecordReferenceAdmission(
      RuntimeRequestContext context,
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy permissions,
      RecordCalculationAccess calculations,
      RecordSqlCompiler compiler,
      org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec schemas) {
    this.context = context;
    this.models = models;
    this.permissions = permissions;
    this.calculations = calculations;
    this.compiler = compiler;
    this.schemas = schemas;
  }

  Plan prepare(
      RuntimeRecordModel root,
      List<RuntimeRecordModel> children,
      String tenant,
      RecordCalculationStore.Installed sourcePolicy,
      BiFunction<RuntimeRecordModel, RecordAccessContext, TargetSnapshot> loader) {
    var actor = context.requireActor();
    if (!actor.tenantId().equals(tenant) || sourcePolicy == null) {
      throw incompatible();
    }
    var definition = sourcePolicy.policy().definition();
    Map<String, RuntimeRecordModel> sources = new LinkedHashMap<>();
    sources.put(root.physicalModelId(), root);
    children.forEach(child -> sources.put(child.physicalModelId(), child));
    Map<String, Binding> bindings = new LinkedHashMap<>();
    Map<String, Target> resolved = new LinkedHashMap<>();
    for (var rule : definition.referencesOrEmpty()) {
      String sourceId =
          rule.source().scope().equals("parent")
              ? root.physicalModelId()
              : definition.details().containsKey(rule.source().scope())
                  ? definition.details().get(rule.source().scope()).physicalModelId()
                  : null;
      var source = sources.get(sourceId);
      if (source == null
          || source.activeSchema().references().stream()
              .noneMatch(
                  reference ->
                      reference.relationId().equals(rule.relationId())
                          && reference.fieldId().equals(rule.source().fieldId())
                          && reference.targetModelId().equals(rule.targetModelId()))) {
        throw incompatible();
      }
      var placement = source.target().referenceTargets().get(rule.targetModelId());
      if (placement == null) {
        throw incompatible();
      }
      if (rule.retired()) {
        if (bindings.putIfAbsent(rule.relationId(), new Binding(rule, sourceId, null)) != null) {
          throw incompatible();
        }
        continue;
      }
      String key = rule.targetModelId() + ":" + rule.targetResourceCode();
      if (!resolved.containsKey(key)) {
        var labelFields =
            definition.referencesOrEmpty().stream()
                .filter(
                    item ->
                        item.targetModelId().equals(rule.targetModelId())
                            && item.targetResourceCode().equals(rule.targetResourceCode()))
                .map(RecordReferenceRule::snapshotLabelFieldId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        resolved.put(key, resolve(rule, labelFields, loader));
      }
      var target = resolved.get(key);
      if (target != null) {
        RecordReferenceRules.requireTargetIdentity(rule, source, target.model());
      }
      if (bindings.putIfAbsent(rule.relationId(), new Binding(rule, sourceId, target)) != null) {
        throw incompatible();
      }
    }
    if (bindings.size()
        != sources.values().stream()
            .mapToInt(model -> model.activeSchema().references().size())
            .sum()) {
      throw incompatible();
    }
    return new Plan(sourcePolicy, Map.copyOf(sources), Map.copyOf(bindings));
  }

  private Target resolve(
      RecordReferenceRule rule,
      java.util.Set<String> labelFields,
      BiFunction<RuntimeRecordModel, RecordAccessContext, TargetSnapshot> loader) {
    var authorization = AuthorizationContextHolder.getContext();
    if (authorization == null
        || authorization.dataScopeFor(rule.targetResourceCode()) == null
        || authorization.fieldPermissionsFor(rule.targetResourceCode()) == null) {
      return null;
    }
    try {
      var model = models.resolveCurrent(context.requireActor(), rule.targetModelId());
      if (!model.appId().equals(rule.targetAppId())
          || !model.formId().equals(rule.targetFormId())) {
        throw incompatible();
      }
      var access =
          permissions.authorizeReference(context.requireActor(), model, rule.targetResourceCode());
      // Published APP entrances have a managed generation; legacy arbitrary model bindings do not
      // become a substitute entrance for an installed business relation.
      if (access.resourceGeneration() < 1) {
        return null;
      }
      var snapshot = loader.apply(model, access);
      var rules = calculations.resolveReference(model, access, snapshot.calculation());
      access = rules.apply(model, access);
      var target = new Target(model, access, snapshot);
      var calculation = calculations.binding(model, access, snapshot.calculation());
      Map<String, RecordReferenceTargetRead> labels = new LinkedHashMap<>();
      for (var fieldId : labelFields) {
        var field =
            model.requestedSchema().fields().stream()
                .filter(value -> value.fieldId().equals(fieldId))
                .findFirst()
                .orElse(null);
        if (field != null
            && access.readableFields().contains(field.code())
            && !access.maskedFields().contains(field.code())) {
          labels.put(
              fieldId,
              RecordReferenceMappingTargets.prepare(
                  target,
                  calculation,
                  permissions,
                  rules,
                  java.util.Set.of(fieldId),
                  compiler,
                  schemas));
        }
      }
      return new Target(model, access, snapshot, Map.copyOf(labels));
    } catch (AccessDeniedException unavailable) {
      return null;
    } catch (DnaRuntimeException failure) {
      if (List.of(
              DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
              DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
              DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE)
          .contains(failure.errorCode())) {
        return null;
      }
      throw failure;
    }
  }

  static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "The installed reference admission policy or storage binding is unavailable");
  }

  record TargetSnapshot(long revision, RecordCalculationStore.Installed calculation) {}

  record Target(
      RuntimeRecordModel model,
      RecordAccessContext access,
      TargetSnapshot snapshot,
      Map<String, RecordReferenceTargetRead> labels) {
    Target(RuntimeRecordModel model, RecordAccessContext access, TargetSnapshot snapshot) {
      this(model, access, snapshot, Map.of());
    }

    Target {
      labels = Map.copyOf(labels);
    }
  }

  record Binding(RecordReferenceRule rule, String sourceModelId, Target target) {}

  record Plan(
      RecordCalculationStore.Installed policy,
      Map<String, RuntimeRecordModel> sources,
      Map<String, Binding> bindings) {
    List<Target> targets() {
      return bindings.values().stream()
          .map(Binding::target)
          .filter(Objects::nonNull)
          .distinct()
          .toList();
    }

    void verify(RecordCalculationStore.Installed installed) {
      if (!Objects.equals(policy, installed)) {
        throw incompatible();
      }
    }

    RecordReferenceMappingBinding mapping(String relationId) {
      return RecordReferenceMappingBinding.bind(this, relationId);
    }
  }
}
