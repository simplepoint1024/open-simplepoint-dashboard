package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceDetail;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferencePage;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Service;

/** Server-owned bounded lookup, using target row/field authorization and the shared DNA reader. */
@Service
public class ApplicationFormReferenceServiceImpl implements ApplicationFormReferenceService {
  private final PublishedApplicationFormResolver forms;
  private final FormRelationTargetResolver relations;
  private final DnaInvocationContextFactory contexts;
  private final DnaFormRuntimeService dna;
  private final ApplicationFormRecordServiceImpl records;
  private final ApplicationFormRuntimeServiceImpl runtime;
  private final FormRecordReadAuthority readAuthority;

  /** Composes authorized reference lookup and detail reads over the existing record runtime. */
  public ApplicationFormReferenceServiceImpl(
      PublishedApplicationFormResolver forms,
      FormRelationTargetResolver relations,
      DnaInvocationContextFactory contexts,
      DnaFormRuntimeService dna,
      ApplicationFormRecordServiceImpl records,
      ApplicationFormRuntimeServiceImpl runtime,
      FormRecordReadAuthority readAuthority) {
    this.forms = forms;
    this.relations = relations;
    this.contexts = contexts;
    this.dna = dna;
    this.records = records;
    this.runtime = runtime;
    this.readAuthority = readAuthority;
  }

  @Override
  public FormReferenceDetail detail(
      String applicationId,
      String pageId,
      String relationId,
      String recordId,
      ApplicationFormRuntimeIdentity identity) {
    String id;
    try {
      id = UUID.fromString(recordId).toString();
      if (!id.equalsIgnoreCase(recordId)) {
        throw invalid();
      }
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw invalid();
    }
    var read = read(applicationId, pageId, relationId, identity, true);
    // Candidate filters only narrow selection. Current target authorization governs opening a
    // saved reference (or a prospective selection), even if it no longer qualifies for new writes.
    var record = records.getDecorated(read.target(), id);
    return new FormReferenceDetail(runtime.describe(read.target(), null), record);
  }

  @Override
  public FormReferencePage search(
      String applicationId,
      String pageId,
      String relationId,
      String keyword,
      int page,
      int size,
      ApplicationFormRuntimeIdentity runtime) {
    return searchContext(applicationId, pageId, relationId, keyword, page, size, runtime, null);
  }

  @Override
  public FormReferencePage searchContext(
      String applicationId,
      String pageId,
      String relationId,
      String keyword,
      int page,
      int size,
      ApplicationFormRuntimeIdentity runtime,
      org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext context) {
    if (page < 0
        || size < 1
        || size > 100
        || keyword != null && keyword.codePointCount(0, keyword.length()) > 200) {
      throw invalid();
    }
    var read = read(applicationId, pageId, relationId, runtime);
    FormReferenceContextPolicy.validate(read.source().document(), read.relation(), read.fields());
    var authorization = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var sourceContext = contexts.forForm(read.source());
    read.view()
        .contextFilters()
        .forEach(
            filter ->
                requireReadable(
                    read.target().physicalModelId(),
                    read.fields().get(filter.targetFieldId()),
                    read.context()));
    var candidate =
        FormReferenceContextPolicy.resolve(
            read.source().document(),
            read.relation(),
            read.fields(),
            context,
            authorization == null ? null : authorization.getAttribute("X-Org-Dept-Id"),
            (scope, field) -> {
              String model = read.source().physicalModelId();
              if (scope
                  == org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter
                      .Source.ROW) {
                model =
                    read.source().detailBindings().stream()
                        .filter(
                            detail ->
                                detail.detailId().equals(read.relation().sourceField().detailId()))
                        .findFirst()
                        .orElseThrow(ApplicationFormReferenceServiceImpl::denied)
                        .physicalModelId();
              }
              requireReadable(model, field, sourceContext);
            });
    if (!candidate.ready()) {
      return new FormReferencePage(page, size, 0, List.of());
    }
    String text = keyword == null ? "" : keyword.strip();
    RecordFilter search = null;
    if (!text.isEmpty()) {
      if (read.view().searchFieldIds().isEmpty()) {
        throw invalid();
      }
      var filters =
          read.view().searchFieldIds().stream()
              .map(
                  id -> {
                    var field = read.fields().get(id);
                    requireReadable(read.target().physicalModelId(), field, read.context());
                    return new RecordFilter(
                        RecordFilterOperator.LIKE,
                        field.storageCode(),
                        List.of((Object) text),
                        List.of());
                  })
              .toList();
      search =
          filters.size() == 1
              ? filters.getFirst()
              : new RecordFilter(RecordFilterOperator.OR, null, List.of(), filters);
    }
    return page(read, page, size, search, candidate.condition());
  }

  @Override
  public List<FormReferenceOption> resolve(
      String applicationId,
      String pageId,
      String relationId,
      List<String> recordIds,
      ApplicationFormRuntimeIdentity runtime) {
    if (recordIds == null || recordIds.size() > 100) {
      throw invalid();
    }
    var ids = new LinkedHashSet<String>();
    try {
      for (String id : recordIds) {
        if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) {
          throw invalid();
        }
        ids.add(UUID.fromString(id).toString());
      }
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    var read = read(applicationId, pageId, relationId, runtime);
    if (ids.isEmpty()) {
      return List.of();
    }
    // Hydration is intentionally independent of browse/qualification predicates. It still uses
    // current target authorization; unavailable and nonexistent identities both remain absent.
    var results =
        page(
            read,
            0,
            ids.size(),
            new RecordFilter(RecordFilterOperator.IN, "id", new ArrayList<>(ids), List.of()),
            null);
    Map<String, FormReferenceOption> byId = new LinkedHashMap<>();
    results.records().forEach(option -> byId.put(option.id(), option));
    return ids.stream().filter(byId::containsKey).map(byId::get).toList();
  }

  private Read read(
      String applicationId,
      String pageId,
      String relationId,
      ApplicationFormRuntimeIdentity runtime) {
    return read(applicationId, pageId, relationId, runtime, false);
  }

  private Read read(
      String applicationId,
      String pageId,
      String relationId,
      ApplicationFormRuntimeIdentity runtime,
      boolean drillThrough) {
    var source = forms.resolve(applicationId, pageId);
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, source.formId(), source.formVersion());
    var relation =
        source.document().relations().stream()
            .filter(item -> item.relationId().equals(relationId))
            .findFirst()
            .orElseThrow(ApplicationFormReferenceServiceImpl::denied);
    requireSourceField(source, relation); // No target reads for a hidden/masked source reference.
    if (drillThrough
        && (relation.referenceView() == null || !relation.referenceView().allowDrillThrough())) {
      throw denied();
    }
    var target = relations.resolve(source, relationId).target();
    var view = relation.referenceView();
    var fields = FormReferenceViewPolicy.validate(view, target.document());
    var context = contexts.forRelatedRead(source, target);
    if (context == null) {
      throw denied();
    }
    requireReadable(target.physicalModelId(), fields.get(view.labelFieldId()), context);
    return new Read(source, relation, target, view, fields, context);
  }

  private void requireSourceField(
      PublishedApplicationForm source, FormRelationDefinition relation) {
    var reference = relation.sourceField();
    if (reference == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_UNAVAILABLE,
          "Collection lookup requires the collection relation runtime");
    }
    var fields = source.document().fields();
    String model = source.physicalModelId();
    if (reference.detailId() != null) {
      fields =
          source.document().details().stream()
              .filter(detail -> detail.detailId().equals(reference.detailId()))
              .findFirst()
              .orElseThrow(ApplicationFormReferenceServiceImpl::denied)
              .fields();
      model =
          source.detailBindings().stream()
              .filter(detail -> detail.detailId().equals(reference.detailId()))
              .findFirst()
              .orElseThrow(ApplicationFormReferenceServiceImpl::denied)
              .physicalModelId();
    }
    var field =
        fields.stream()
            .filter(item -> item.fieldId().equals(reference.fieldId()))
            .findFirst()
            .orElseThrow(ApplicationFormReferenceServiceImpl::denied);
    requireReadable(model, field, contexts.forForm(source));
  }

  private FormReferencePage page(
      Read read, int page, int size, RecordFilter filter, RecordValueCondition condition) {
    var projected = new LinkedHashSet<String>();
    projected.add(read.view().labelFieldId());
    read.view().displayFieldIds().stream()
        .filter(
            id -> readable(read.target().physicalModelId(), read.fields().get(id), read.context()))
        .forEach(projected::add);
    var columns = projected.stream().map(id -> read.fields().get(id).storageCode()).toList();
    try {
      var query =
          new RecordPageQuery(
              read.target().physicalModelId(),
              new RecordSchemaReference(
                  read.target().physicalModelVersion(), read.target().storageChecksum()),
              page,
              size,
              columns,
              filter,
              List.of(),
              condition);
      var authority =
          readAuthority.current(
              read.target(),
              read.context(),
              new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
                  "1", null, query, null, 30_000),
              FormRecordReadAuthority.deadline());
      var result = dna.pageConditionedRecords(authority, query);
      return new FormReferencePage(
          result.page(),
          result.size(),
          result.total(),
          result.records().stream().map(record -> option(read, projected, record)).toList());
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    }
  }

  private static FormReferenceOption option(Read read, Set<String> fields, RecordView record) {
    var values = new LinkedHashMap<String, Object>();
    for (String field : fields) {
      String column = read.fields().get(field).storageCode();
      if (record.values().containsKey(column)) {
        values.put(field, record.values().get(column));
      }
    }
    Object label = values.get(read.view().labelFieldId());
    return new FormReferenceOption(
        record.id(),
        record.version(),
        label == null ? record.id() : label.toString(),
        values,
        read.view().allowDrillThrough());
  }

  private static boolean readable(
      String model, FormFieldDefinition field, DnaInvocationContext context) {
    String access =
        RecordPermissionModel.access(
            context.fieldPermissions(), model, field.fieldId(), field.storageCode());
    return !"HIDDEN".equals(access) && !"MASKED".equals(access);
  }

  private static void requireReadable(
      String model, FormFieldDefinition field, DnaInvocationContext context) {
    if (!readable(model, field, context)) {
      throw denied();
    }
  }

  private static ApplicationFormException denied() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "The business reference is unavailable");
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID,
        "Reference lookup exceeds its declared query contract");
  }

  private record Read(
      PublishedApplicationForm source,
      FormRelationDefinition relation,
      PublishedApplicationForm target,
      FormReferenceView view,
      Map<String, FormFieldDefinition> fields,
      DnaInvocationContext context) {}
}
