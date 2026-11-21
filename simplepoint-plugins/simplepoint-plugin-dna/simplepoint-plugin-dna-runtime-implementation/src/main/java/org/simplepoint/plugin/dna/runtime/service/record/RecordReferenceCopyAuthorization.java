package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.security.access.AccessDeniedException;

/** Current permission checks of frozen copy dependencies; never reads replacement field values. */
final class RecordReferenceCopyAuthorization {
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy policies;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationAccess calculations;

  RecordReferenceCopyAuthorization(
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationAccess calculations) {
    this.models = models;
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.calculations = calculations;
  }

  Set<RecordReferenceCopyOrigin> authorize(
      RuntimeActor actor,
      Set<RecordReferenceCopyOrigin> origins,
      RecordExecutionDeadline deadline) {
    if (origins.isEmpty()) {
      return Set.of();
    }
    if (origins.size() > 10_050
        || origins.stream().mapToInt(origin -> origin.requirements().size()).sum() > 16_384) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    var requirements =
        origins.stream()
            .flatMap(origin -> origin.requirements().stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    var allowed = authorizeRequirements(actor, requirements, deadline);
    return origins.stream()
        .filter(origin -> allowed.containsAll(origin.requirements()))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  Set<RecordReferenceReadRequirement> authorizeRequirements(
      RuntimeActor actor,
      Set<RecordReferenceReadRequirement> requirements,
      RecordExecutionDeadline deadline) {
    if (requirements.size() > 16384) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Map<Target, Set<RecordReferenceReadRequirement>> groups = new LinkedHashMap<>();
    requirements.forEach(
        requirement ->
            groups
                .computeIfAbsent(
                    new Target(
                        requirement.modelId(),
                        requirement.appId(),
                        requirement.formId(),
                        requirement.resourceCode()),
                    ignored -> new LinkedHashSet<>())
                .add(requirement));
    if (groups.size() > 128) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Set<RecordReferenceReadRequirement> allowed = new LinkedHashSet<>();
    for (var entry : groups.entrySet()) {
      deadline.remainingMillis();
      var target = entry.getKey();
      var context = AuthorizationContextHolder.getContext();
      if (context == null
          || context.dataScopeFor(target.resource()) == null
          || context.fieldPermissionsFor(target.resource()) == null) {
        continue;
      }
      try {
        var parent = models.resolveCurrent(actor, target.model());
        if (!parent.appId().equals(target.app()) || !parent.formId().equals(target.form())) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        var access = policies.authorizeReference(actor, parent, target.resource());
        if (access.resourceGeneration() < 1 || !identityReadable(access)) {
          continue;
        }
        var children = children(parent, access, entry.getValue());
        allowed.addAll(
            backend.read(
                parent,
                children.values().stream().map(Child::model).toList(),
                access,
                deadline.remainingMillis(),
                session ->
                    authorizeGroup(parent, access, children, entry.getValue(), session, deadline)));
      } catch (AccessDeniedException unavailable) {
        // Denied dependencies hide only their consuming values in the source projection.
      } catch (DnaRuntimeException failure) {
        if (!unavailable(failure.errorCode())) {
          throw failure;
        }
      }
    }
    deadline.remainingMillis();
    return Set.copyOf(allowed);
  }

  /** Local inputs keep source field policy; historical owned rows need not still exist. */
  Set<RecordDerivedCopyOrigin> readableDerived(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      String parentRecordId,
      Set<RecordDerivedCopyOrigin> origins,
      RecordExecutionDeadline deadline) {
    return readableDerivedBatch(parent, access, Map.of(parentRecordId, origins), deadline)
        .get(parentRecordId);
  }

  Map<String, Set<RecordDerivedCopyOrigin>> readableDerivedBatch(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      Map<String, Set<RecordDerivedCopyOrigin>> rows,
      RecordExecutionDeadline deadline) {
    var origins =
        rows.values().stream()
            .flatMap(Set::stream)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (rows.size() > 500) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    if (origins.size() > 10050) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Set<String> requested = new LinkedHashSet<>();
    origins.forEach(
        origin ->
            origin
                .localRequirements()
                .forEach(requirement -> requested.add(requirement.modelId())));
    if (requested.size() > 6) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Map<String, RuntimeRecordModel> localModels = new LinkedHashMap<>();
    Map<String, RecordAccessContext> grants = new LinkedHashMap<>();
    localModels.put(parent.physicalModelId(), parent);
    grants.put(parent.physicalModelId(), access);
    for (String modelId : requested) {
      if (modelId.equals(parent.physicalModelId())) {
        continue;
      }
      deadline.remainingMillis();
      try {
        var model = models.resolveCurrent(access.actor(), modelId);
        OwnedRecordBinding.parentCode(parent, model);
        localModels.put(modelId, model);
        grants.put(modelId, policies.owned(access.actor(), model, access));
      } catch (AccessDeniedException unavailable) {
        // An unavailable original input never becomes an empty, authorized dependency.
      } catch (DnaRuntimeException failure) {
        if (!unavailable(failure.errorCode())) {
          throw failure;
        }
      }
    }
    Map<String, Set<RecordDerivedCopyOrigin>> result = new LinkedHashMap<>();
    rows.forEach(
        (parentRecordId, values) ->
            result.put(
                parentRecordId,
                values.stream()
                    .filter(
                        origin ->
                            origin.localRequirements().stream()
                                .allMatch(
                                    requirement -> {
                                      var model = localModels.get(requirement.modelId());
                                      return model != null
                                          && (!model
                                                  .physicalModelId()
                                                  .equals(parent.physicalModelId())
                                              || parentRecordId.equals(requirement.recordId()))
                                          && readable(
                                              model,
                                              grants.get(requirement.modelId()),
                                              requirement.fieldIds());
                                    }))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet())));
    return Map.copyOf(result);
  }

  private Map<String, Child> children(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      Set<RecordReferenceReadRequirement> requirements) {
    Map<String, Child> children = new LinkedHashMap<>();
    var identities =
        requirements.stream()
            .map(RecordReferenceReadRequirement::detailModelId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    if (identities.size() > 5) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    for (String id : identities) {
      try {
        var child = models.resolveCurrent(access.actor(), id);
        String parentCode = OwnedRecordBinding.parentCode(parent, child);
        children.put(
            id, new Child(child, policies.owned(access.actor(), child, access), parentCode));
      } catch (AccessDeniedException unavailable) {
        // Retired or unavailable owned dependencies are not silently treated as empty collections.
      } catch (DnaRuntimeException failure) {
        if (!unavailable(failure.errorCode())) {
          throw failure;
        }
      }
    }
    return children;
  }

  private Set<RecordReferenceReadRequirement> authorizeGroup(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      Map<String, Child> children,
      Set<RecordReferenceReadRequirement> requirements,
      RecordReadSession session,
      RecordExecutionDeadline deadline) {
    var rules = calculations.resolveReference(parent, access, session.calculation());
    var rootAccess = rules.apply(parent, access);
    if (!identityReadable(rootAccess)) {
      return Set.of();
    }
    List<RecordReferenceReadRequirement> readable = new ArrayList<>();
    Map<String, RecordAccessContext> ownedAccess = new LinkedHashMap<>();
    children.forEach(
        (id, child) -> ownedAccess.put(id, rules.apply(child.model(), child.access())));
    for (var requirement : requirements) {
      var child = children.get(requirement.detailModelId());
      var model =
          requirement.detailModelId() == null ? parent : child == null ? null : child.model();
      var policy =
          requirement.detailModelId() == null
              ? rootAccess
              : ownedAccess.get(requirement.detailModelId());
      if (model != null && readable(model, policy, requirement.fieldIds())) {
        readable.add(requirement);
      }
    }
    var rootIds =
        readable.stream().map(RecordReferenceReadRequirement::recordId).distinct().toList();
    Set<String> visibleRoots = new LinkedHashSet<>();
    for (int offset = 0; offset < rootIds.size(); offset += 100) {
      deadline.remainingMillis();
      visibleRoots.addAll(
          RecordReferenceVisibility.select(
              parent,
              rootAccess,
              rootIds.subList(offset, Math.min(offset + 100, rootIds.size())),
              compiler,
              session.query()));
    }
    Set<RecordReferenceReadRequirement> allowed = new LinkedHashSet<>();
    Map<String, List<RecordReferenceReadRequirement>> ownedRows = new LinkedHashMap<>();
    for (var requirement : readable) {
      if (!visibleRoots.contains(requirement.recordId())) {
        continue;
      }
      if (requirement.detailRecordId() == null) {
        allowed.add(requirement);
      } else {
        ownedRows
            .computeIfAbsent(requirement.detailModelId(), ignored -> new ArrayList<>())
            .add(requirement);
      }
    }
    for (var entry : ownedRows.entrySet()) {
      var child = children.get(entry.getKey());
      var requested =
          entry.getValue().stream()
              .map(
                  requirement ->
                      new RecordReferenceOwnedVisibility.Row(
                          requirement.detailRecordId(), requirement.recordId()))
              .distinct()
              .toList();
      Set<RecordReferenceOwnedVisibility.Row> visible = new LinkedHashSet<>();
      for (int offset = 0; offset < requested.size(); offset += 100) {
        deadline.remainingMillis();
        visible.addAll(
            RecordReferenceOwnedVisibility.select(
                child.model(),
                ownedAccess.get(entry.getKey()),
                child.parentCode(),
                requested.subList(offset, Math.min(offset + 100, requested.size())),
                compiler,
                session.query()));
      }
      entry.getValue().stream()
          .filter(
              requirement ->
                  visible.contains(
                      new RecordReferenceOwnedVisibility.Row(
                          requirement.detailRecordId(), requirement.recordId())))
          .forEach(allowed::add);
    }
    return Set.copyOf(allowed);
  }

  private static boolean readable(
      RuntimeRecordModel model, RecordAccessContext access, List<String> fields) {
    return identityReadable(access)
        && fields.stream()
            .allMatch(
                id ->
                    model.requestedSchema().fields().stream()
                        .anyMatch(
                            field ->
                                field.fieldId().equals(id)
                                    && access.readableFields().contains(field.code())
                                    && !access.maskedFields().contains(field.code())));
  }

  private static boolean identityReadable(RecordAccessContext access) {
    return access != null
        && access.readableFields().contains("id")
        && !access.maskedFields().contains("id");
  }

  private static boolean unavailable(DnaRuntimeErrorCode code) {
    return Set.of(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
            DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED)
        .contains(code);
  }

  private record Target(String model, String app, String form, String resource) {}

  private record Child(RuntimeRecordModel model, RecordAccessContext access, String parentCode) {}
}
