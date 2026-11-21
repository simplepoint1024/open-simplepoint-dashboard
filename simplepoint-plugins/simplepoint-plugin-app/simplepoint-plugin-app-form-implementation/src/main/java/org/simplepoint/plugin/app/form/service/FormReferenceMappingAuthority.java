package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.springframework.stereotype.Component;

/** Resolves transitive copy authority in bounded batches without observing or changing values. */
@Component
public class FormReferenceMappingAuthority {
  private final DnaInvocationContextFactory contexts;
  private final FormRelationTargetResolver relations;
  private final FormRecordReadAuthority reads;

  /** Keeps mutable input discovery separate from receipt recovery and immutable history. */
  public FormReferenceMappingAuthority(
      DnaInvocationContextFactory contexts,
      FormRelationTargetResolver relations,
      FormRecordReadAuthority reads) {
    this.contexts = contexts;
    this.relations = relations;
    this.reads = reads;
  }

  DnaInvocationContext observe(
      PublishedApplicationForm form,
      FormRelationDefinition relation,
      String targetRecordId,
      List<String> defaults) {
    return resolve(
        form,
        Map.of(
            relation.relationId(),
            new Selection(
                targetRecordId == null ? Set.of() : Set.of(targetRecordId), Set.copyOf(defaults))));
  }

  /** Call only after exact receipt lookup; a committed retry must not read mutable inputs. */
  DnaInvocationContext forWrite(PublishedApplicationForm form, MappedOwnedRecordCommand command) {
    var selected = new LinkedHashMap<String, Selection>();
    for (var confirmation : command.confirmations()) {
      var group =
          selected.computeIfAbsent(
              confirmation.relationId(),
              ignored -> new Selection(new LinkedHashSet<>(), new LinkedHashSet<>()));
      if (confirmation.observation().targetRecordId() != null) {
        group.ids().add(confirmation.observation().targetRecordId());
      }
      group.defaults().addAll(confirmation.applyDefaultMappingIds());
    }
    return resolve(form, selected);
  }

  private DnaInvocationContext resolve(
      PublishedApplicationForm form, Map<String, Selection> selected) {
    long deadline = FormRecordReadAuthority.deadline();
    var source = contexts.forForm(form);
    // Check every selected source field before touching any target, including earlier groups.
    var definitions = new LinkedHashMap<String, FormRelationDefinition>();
    for (var entry : selected.entrySet()) {
      var relation =
          form.document().relations().stream()
              .filter(value -> value.relationId().equals(entry.getKey()))
              .findFirst()
              .orElseThrow(FormReferenceMappingAuthority::denied);
      writable(form, relation.sourceField(), source.fieldPermissions());
      for (var mapping : relation.mappings()) {
        if (mapping.mode() == FormReferenceMapping.Mode.SERVER_SNAPSHOT
            || entry.getValue().defaults().contains(mapping.mappingId())) {
          writable(
              form,
              new FormRelationField(
                  relation.sourceField().detailId(), mapping.destinationFieldId()),
              source.fieldPermissions());
        }
      }
      definitions.put(entry.getKey(), relation);
    }
    var participants = new LinkedHashSet<>(relations.availableForWrite(form));
    for (var entry : selected.entrySet()) {
      if (entry.getValue().ids().isEmpty()) {
        continue;
      }
      var definition = definitions.get(entry.getKey());
      var mappedFields =
          definition.mappings().stream()
              .filter(
                  mapping ->
                      mapping.mode() == FormReferenceMapping.Mode.SERVER_SNAPSHOT
                          || entry.getValue().defaults().contains(mapping.mappingId()))
              .map(FormReferenceMapping::targetFieldId)
              .distinct()
              .toList();
      if (mappedFields.isEmpty()) {
        continue;
      }
      var target = relations.resolve(form, entry.getKey()).target();
      var targetContext = contexts.forRelatedRead(form, target);
      if (targetContext == null) {
        throw denied();
      }
      var fields =
          mappedFields.stream()
              .map(
                  id ->
                      target.document().fields().stream()
                          .filter(field -> field.fieldId().equals(id) && field.persistent())
                          .findFirst()
                          .orElseThrow(FormReferenceMappingAuthority::denied)
                          .storageCode())
              .toList();
      var ids = new ArrayList<>(entry.getValue().ids());
      for (int offset = 0; offset < ids.size(); offset += 100) {
        List<Object> batch =
            new ArrayList<>(ids.subList(offset, Math.min(offset + 100, ids.size())));
        var query =
            new RecordPageQuery(
                target.physicalModelId(),
                new RecordSchemaReference(target.physicalModelVersion(), target.storageChecksum()),
                0,
                100,
                fields,
                new RecordFilter(RecordFilterOperator.IN, "id", batch, List.of()),
                List.of());
        participants.addAll(
            reads.originals(
                target,
                targetContext,
                new RecordCurrentTargetsQuery("1", null, query, null, 30_000),
                deadline));
        if (participants.size() > 50) {
          throw denied();
        }
      }
    }
    FormRecordReadAuthority.remaining(deadline);
    return contexts.forRelations(form, participants);
  }

  private static void writable(
      PublishedApplicationForm form, FormRelationField field, Map<String, String> permissions) {
    var selected = FormRelationSourceField.resolve(form, field);
    var access =
        RecordPermissionModel.access(
            permissions,
            selected.modelId(),
            selected.field().fieldId(),
            selected.field().storageCode());
    if (access != null && !"EDITABLE".equals(access)) {
      throw denied();
    }
  }

  private static ApplicationFormException denied() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "Reference mapping participants are unavailable under the current field policy");
  }

  private record Selection(Set<String> ids, Set<String> defaults) {}
}
