package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSnapshot;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceVisibilityQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Revalidates target authorization on every APP history read, retaining original label evidence.
 */
@Service
public class FormReferenceSnapshotAccess {
  private final FormRelationTargetResolver relations;
  private final DnaInvocationContextFactory contexts;
  private final DnaFormRuntimeService dna;

  /** Uses frozen APP entry identities and the existing authenticated DNA record boundary. */
  public FormReferenceSnapshotAccess(
      FormRelationTargetResolver relations,
      DnaInvocationContextFactory contexts,
      DnaFormRuntimeService dna) {
    this.relations = relations;
    this.contexts = contexts;
    this.dna = dna;
  }

  List<FormReferenceSnapshot> filter(
      PublishedApplicationForm form,
      ApplicationFormRecordView projected,
      List<FormReferenceSnapshot> captured) {
    var visible = FormReferenceSnapshots.visible(form, projected, captured);
    if (visible.isEmpty()) {
      return List.of();
    }
    long deadline = System.nanoTime() + 30_000_000_000L;
    Map<String, List<FormReferenceSnapshot>> grouped = new LinkedHashMap<>();
    visible.forEach(
        label ->
            grouped.computeIfAbsent(label.relationId(), ignored -> new ArrayList<>()).add(label));
    Set<FormReferenceSnapshot> allowed = new HashSet<>();
    for (var group : grouped.entrySet()) {
      remaining(deadline);
      try {
        var target = relations.resolve(form, group.getKey()).target();
        var labelId = group.getValue().getFirst().labelFieldId();
        var field =
            target.document().fields().stream()
                .filter(value -> value.fieldId().equals(labelId))
                .findFirst()
                .orElse(null);
        if (field == null) {
          continue;
        }
        var authority = contexts.forRelatedRead(form, target);
        if (authority == null) {
          continue;
        }
        String policy =
            RecordPermissionModel.access(
                authority.fieldPermissions(),
                target.physicalModelId(),
                labelId,
                field.storageCode());
        if ("HIDDEN".equals(policy) || "MASKED".equals(policy)) {
          continue;
        }
        var ids =
            new ArrayList<>(
                new LinkedHashSet<>(
                    group.getValue().stream().map(FormReferenceSnapshot::targetRecordId).toList()));
        Set<String> found = new HashSet<>();
        for (int offset = 0; offset < ids.size(); offset += 100) {
          var batch = ids.subList(offset, Math.min(offset + 100, ids.size()));
          var returned =
              dna.visibleReferenceRecords(
                  authority,
                  new RecordReferenceVisibilityQuery(
                      target.physicalModelId(),
                      new RecordSchemaReference(
                          target.physicalModelVersion(), target.storageChecksum()),
                      labelId,
                      batch,
                      remaining(deadline)));
          if (returned == null
              || returned.size() > batch.size()
              || new HashSet<>(returned).size() != returned.size()
              || !batch.containsAll(returned)) {
            throw new ApplicationFormException(
                ApplicationFormErrorCode.PERSISTENCE_FAILED, "Invalid reference visibility result");
          }
          found.addAll(returned);
        }
        group.getValue().stream()
            .filter(label -> found.contains(label.targetRecordId()))
            .forEach(allowed::add);
      } catch (AccessDeniedException unavailable) {
        // Omit this target without hiding other authorized source or target evidence.
      } catch (ApplicationFormException failure) {
        if (!unavailable(failure.errorCode())) {
          throw failure;
        }
      } catch (DnaRuntimeException failure) {
        if (!Set.of(
                DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
                DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
                DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
                DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED)
            .contains(failure.errorCode())) {
          throw failure;
        }
      }
    }
    remaining(deadline);
    return visible.stream().filter(allowed::contains).toList();
  }

  private static int remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_UNAVAILABLE, "Reference history read budget exhausted");
    }
    return (int) Math.max(1, Math.min(30_000, nanos / 1_000_000L));
  }

  private static boolean unavailable(ApplicationFormErrorCode code) {
    return Set.of(
            ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
            ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
            ApplicationFormErrorCode.FORM_NOT_FOUND,
            ApplicationFormErrorCode.FORM_ARCHIVED,
            ApplicationFormErrorCode.VERSION_NOT_FOUND,
            ApplicationFormErrorCode.PAGE_NOT_FOUND,
            ApplicationFormErrorCode.PAGE_NOT_PUBLISHED,
            ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
            ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED)
        .contains(code);
  }
}
