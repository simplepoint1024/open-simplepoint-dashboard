package org.simplepoint.plugin.app.form.service;

import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.springframework.stereotype.Component;

/**
 * Resolves both authorized entries and the saved parent without transferring either page's grants.
 */
@Component
public class FormInverseRelationResolver {
  private final PublishedApplicationFormResolver forms;
  private final FormRelationTargetResolver relations;
  private final ApplicationFormRecordServiceImpl records;
  private final DnaInvocationContextFactory contexts;

  /** Reuses owning-domain metadata and record authorization; no cross-module persistence access. */
  public FormInverseRelationResolver(
      PublishedApplicationFormResolver forms,
      FormRelationTargetResolver relations,
      ApplicationFormRecordServiceImpl records,
      DnaInvocationContextFactory contexts) {
    this.forms = forms;
    this.relations = relations;
    this.records = records;
    this.contexts = contexts;
  }

  /**
   * The browser chooses only a published view and a parent record, never a source page or field.
   */
  ResolvedView resolve(
      String applicationId,
      String pageId,
      ApplicationFormRuntimeIdentity runtime,
      String viewId,
      String recordId) {
    var parent = forms.resolve(applicationId, pageId);
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, parent.formId(), parent.formVersion());
    return resolve(parent, viewId, recordId(recordId), false);
  }

  private ResolvedView resolve(
      PublishedApplicationForm parent, String viewId, String id, boolean parentChecked) {
    if (parent.document().presentation() == null) {
      throw unavailable();
    }
    var view =
        parent.document().presentation().inverseViews().stream()
            .filter(candidate -> candidate.viewId().equals(viewId))
            .findFirst()
            .orElseThrow(FormInverseRelationResolver::unavailable);
    var source = relations.describeTarget(view.source());
    var owning = relations.resolve(source, view.sourceRelationId());
    var target = owning.target();
    var relation = owning.definition();
    if (!parent.tenantId().equals(source.tenantId())
        || !parent.tenantId().equals(target.tenantId())
        || !parent.applicationId().equals(target.applicationId())
        || !parent.formId().equals(target.formId())
        || !parent.physicalModelId().equals(target.physicalModelId())
        || relation.sourceField() == null) {
      throw unavailable();
    }
    var selected = FormRelationSourceField.resolve(source, relation.sourceField());
    final var field = selected.field();
    final String model = selected.modelId();
    if (!selected.readable(contexts.forForm(source).fieldPermissions())) {
      throw unavailable();
    }
    // A form may have several entries with different data scopes. Both must admit this record.
    if (!parentChecked) {
      records.get(parent, id);
    }
    if (!Objects.equals(parent.pageId(), target.pageId())
        || !Objects.equals(parent.resourceCode(), target.resourceCode())
        || parent.formVersion() != target.formVersion()) {
      records.get(target, id);
    }
    return new ResolvedView(parent, source, view, relation, field, model, id);
  }

  /** A bounded configured directory; denied views disappear without lending the parent grant. */
  java.util.List<org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary> views(
      String applicationId,
      String pageId,
      ApplicationFormRuntimeIdentity runtime,
      String recordId) {
    var parent = forms.resolve(applicationId, pageId);
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, parent.formId(), parent.formVersion());
    String id = recordId(recordId);
    records.get(parent, id);
    if (parent.document().presentation() == null) {
      return java.util.List.of();
    }
    var result =
        new java.util.ArrayList<
            org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary>();
    for (var candidate : parent.document().presentation().inverseViews()) {
      try {
        var resolved = resolve(parent, candidate.viewId(), id, true);
        result.add(
            new org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary(
                resolved.view().viewId(),
                resolved.view().label(),
                resolved.view().labelLocalization()));
      } catch (ApplicationFormException failure) {
        switch (failure.errorCode()) {
          case RESOURCE_ACCESS_DENIED,
              RECORD_NOT_FOUND,
              PAGE_NOT_FOUND,
              FORM_NOT_FOUND,
              PAGE_NOT_PUBLISHED,
              FORM_ARCHIVED -> {
            // Unavailable entries are not navigation options. Infrastructure errors remain visible.
          }
          default -> throw failure;
        }
      }
    }
    return java.util.List.copyOf(result);
  }

  /** Operation metadata is a ceiling; the actual record state is still validated on every write. */
  boolean writable(
      ResolvedView view, org.simplepoint.plugin.app.form.api.model.FormRecordState state) {
    String access =
        RecordPermissionModel.access(
            contexts.forForm(view.source()).fieldPermissions(),
            view.sourceModelId(),
            view.sourceField().fieldId(),
            view.sourceField().storageCode());
    return (access == null || "EDITABLE".equals(access)) && view.sourceField().allowsWrite(state);
  }

  private static String recordId(String value) {
    try {
      var id = UUID.fromString(value).toString();
      if (id.equalsIgnoreCase(value)) {
        return id;
      }
    } catch (IllegalArgumentException | NullPointerException ignored) {
      // Preserve the public stable invalid-record error; never query an incomplete parent ID.
    }
    throw new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID, "A saved parent record identity is required");
  }

  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "The inverse relation view is unavailable");
  }

  /** Detached resolved identities; callers still authorize every query or mutation. */
  record ResolvedView(
      PublishedApplicationForm parent,
      PublishedApplicationForm source,
      FormInverseRelationView view,
      FormRelationDefinition relation,
      FormFieldDefinition sourceField,
      String sourceModelId,
      String parentRecordId) {}
}
