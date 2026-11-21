package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionOperation;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionView;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCollectionService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.stereotype.Service;

@Service
public class ApplicationFormCollectionServiceImpl implements ApplicationFormCollectionService {
  private final PublishedApplicationFormResolver forms;
  private final FormRelationTargetResolver relations;
  private final ApplicationFormRecordServiceImpl records;
  private final ApplicationFormRecordSchemaServiceImpl schemas;
  private final DnaInvocationContextFactory contexts;
  private final DnaOwnedRecordService dna;

  public ApplicationFormCollectionServiceImpl(
      PublishedApplicationFormResolver forms,
      FormRelationTargetResolver relations,
      ApplicationFormRecordServiceImpl records,
      ApplicationFormRecordSchemaServiceImpl schemas,
      DnaInvocationContextFactory contexts,
      DnaOwnedRecordService dna) {
    this.forms = forms;
    this.relations = relations;
    this.records = records;
    this.schemas = schemas;
    this.contexts = contexts;
    this.dna = dna;
  }

  @Override
  public List<FormCollectionView> views(
      String applicationId,
      String pageId,
      ApplicationFormRuntimeIdentity runtime,
      String recordId) {
    var anchor = anchor(applicationId, pageId, runtime);
    var collections =
        anchor.document().relations().stream()
            .filter(relation -> relation.sourceField() == null)
            .toList();
    var inverses = inverseViews(anchor);
    if (collections.isEmpty() && inverses.isEmpty()) {
      return List.of();
    }
    var record = records.get(anchor, recordId);
    var views = new ArrayList<FormCollectionView>();
    for (var relation : collections) {
      addView(
          views,
          anchor,
          new Scope(runtime, recordId, relation.relationId(), false),
          record.state());
    }
    for (var inverse : inverses) {
      addView(views, anchor, new Scope(runtime, recordId, inverse.viewId(), true), record.state());
    }
    return List.copyOf(views);
  }

  private void addView(
      List<FormCollectionView> views,
      PublishedApplicationForm anchor,
      Scope scope,
      FormRecordState state) {
    try {
      var owned = own(anchor, scope);
      var target = target(owned);
      var inverse = owned.reverse();
      var label = owned.definition().collectionLabel();
      boolean mutable = inverse != null || state == FormRecordState.DRAFT;
      views.add(
          new FormCollectionView(
              scope.viewId(),
              inverse != null,
              inverse != null
                  ? inverse.label()
                  : label == null ? target.pageName() : label.defaultValue(),
              inverse != null ? inverse.labelLocalization() : label,
              mutable && allowed(owned, true),
              mutable && allowed(owned, false)));
    } catch (ApplicationFormException failure) {
      switch (failure.errorCode()) {
        case RESOURCE_ACCESS_DENIED,
            RECORD_NOT_FOUND,
            APPLICATION_NOT_FOUND,
            PAGE_NOT_FOUND,
            FORM_NOT_FOUND,
            PAGE_NOT_PUBLISHED,
            FORM_ARCHIVED,
            PAGE_BINDING_CONFLICT,
            PAGE_BINDING_SUPERSEDED,
            VERSION_NOT_FOUND -> {
          // Denied or unavailable relations provide no navigation or aggregate counts.
        }
        default -> throw failure;
      }
    }
  }

  @Override
  public Map<String, Object> schema(
      String applicationId, String pageId, Scope scope, boolean candidates) {
    var owned = own(anchor(applicationId, pageId, scope.runtime()), scope);
    var target = target(owned);
    var record = records.get(owned.anchor(), scope.recordId());
    boolean mutable = owned.reverse() != null || record.state() == FormRecordState.DRAFT;
    if (candidates && !(mutable && allowed(owned, true))) {
      throw unavailable();
    }
    var result = owned.reverse() == null ? target : owned.source();
    var schema =
        schemas.schema(result, new ApplicationFormRuntimeCapabilities(false, false, false, false));
    var actions = new ArrayList<Map<String, Object>>();
    if (candidates) {
      actions.add(
          button(
              "linkSelected",
              "i18n:app.relation.collection.link",
              "LinkOutlined",
              1,
              scope.inverse() ? 1 : 100));
    } else {
      if (mutable && allowed(owned, true)) {
        actions.add(
            button("chooseMembers", "i18n:app.relation.collection.choose", "PlusOutlined", 0, 0));
      }
      if (mutable && allowed(owned, false)) {
        actions.add(
            button(
                "unlinkMembers",
                "i18n:app.runtime.relation.unlink",
                "DisconnectOutlined",
                1,
                scope.inverse() ? 1 : 100));
      }
    }
    return Map.of("schema", schema.get("schema"), "buttons", actions);
  }

  @Override
  public ApplicationFormRecordPageView page(
      String applicationId,
      String pageId,
      Scope scope,
      boolean candidates,
      ApplicationFormRecordPageQuery query) {
    var owned = own(anchor(applicationId, pageId, scope.runtime()), scope);
    if (candidates) {
      requireWrite(owned, true);
    }
    var target = target(owned);
    var result = owned.reverse() == null ? target : owned.source();
    var authority = contexts.forRelations(owned.source(), List.of(target));
    var direction =
        owned.reverse() == null
            ? CollectionRecordPageQuery.Direction.FROM_SOURCE
            : CollectionRecordPageQuery.Direction.FROM_TARGET;
    var candidateView =
        candidates && owned.reverse() == null ? owned.definition().referenceView() : null;
    var condition =
        candidateView == null
            ? null
            : FormReferenceViewPolicy.condition(
                candidateView.candidateFilter(),
                FormReferenceViewPolicy.validate(candidateView, result.document()));
    return records.pageCollection(
        result,
        query,
        contexts.forForm(result),
        condition,
        page ->
            dna.pageCollection(
                authority,
                new CollectionRecordPageQuery(
                    owned.source().physicalModelId(),
                    schema(owned.source()),
                    owned.definition().relationId(),
                    direction,
                    scope.recordId(),
                    page,
                    candidates)));
  }

  @Override
  public FormCollectionOperation.Observation observe(
      String applicationId,
      String pageId,
      Scope scope,
      FormCollectionOperation.Selection selection) {
    var owned = own(anchor(applicationId, pageId, scope.runtime()), scope);
    requireWrite(owned, selection.link());
    var target = target(owned);
    boolean inverse = owned.reverse() != null;
    if (inverse && (selection.memberId() == null || !selection.members().isEmpty())
        || !inverse && selection.memberId() != null) {
      throw invalid(null);
    }
    try {
      var query =
          new CollectionRecordObservationQuery(
              owned.source().physicalModelId(),
              schema(owned.source()),
              owned.definition().relationId(),
              inverse ? selection.memberId() : scope.recordId(),
              inverse ? List.of(scope.recordId()) : selection.members());
      var observed =
          dna.observeCollection(contexts.forRelations(owned.source(), List.of(target)), query);
      if (inverse) {
        var edge = observed.edges().getFirst();
        return new FormCollectionOperation.Observation(
            edge.targetVersion(),
            List.of(
                new FormCollectionOperation.Edge(
                    selection.memberId(),
                    observed.sourceVersion(),
                    edge.edgeVersion(),
                    edge.linked())));
      }
      return new FormCollectionOperation.Observation(
          observed.sourceVersion(),
          observed.edges().stream()
              .map(
                  edge ->
                      new FormCollectionOperation.Edge(
                          edge.targetRecordId(),
                          edge.targetVersion(),
                          edge.edgeVersion(),
                          edge.linked()))
              .toList());
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw invalid(failure);
    }
  }

  @Override
  public void execute(
      String applicationId, String pageId, Scope scope, FormCollectionOperation.Command command) {
    var owned = own(anchor(applicationId, pageId, scope.runtime()), scope);
    requireWrite(owned, command.link());
    boolean inverse = owned.reverse() != null;
    if (inverse && command.edges().size() != 1) {
      throw invalid(null);
    }
    try {
      var mode =
          command.link() ? CollectionEdgeMutation.Mode.LINK : CollectionEdgeMutation.Mode.UNLINK;
      var intent =
          new CollectionRecordCommand(
              "1",
              owned.source().physicalModelId(),
              schema(owned.source()),
              owned.definition().relationId(),
              inverse ? command.edges().getFirst().memberId() : scope.recordId(),
              inverse ? command.edges().getFirst().memberVersion() : command.anchorVersion(),
              command.edges().stream()
                  .map(
                      edge ->
                          new CollectionEdgeMutation(
                              inverse ? scope.recordId() : edge.memberId(),
                              inverse ? command.anchorVersion() : edge.memberVersion(),
                              edge.edgeVersion(),
                              mode))
                  .toList(),
              command.idempotencyKey(),
              30_000);
      if (dna.findCollectionResult(contexts.forForm(owned.source()), intent) == null) {
        var target = target(owned);
        dna.executeCollection(contexts.forRelations(owned.source(), List.of(target)), intent);
      }
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw invalid(failure);
    }
  }

  private PublishedApplicationForm anchor(
      String applicationId, String pageId, ApplicationFormRuntimeIdentity identity) {
    var anchor = forms.resolve(applicationId, pageId);
    ApplicationFormRuntimeIdentity.requireCurrent(identity, anchor.formId(), anchor.formVersion());
    return anchor;
  }

  private Owned own(PublishedApplicationForm anchor, Scope scope) {
    var inverse =
        scope.inverse()
            ? inverseViews(anchor).stream()
                .filter(view -> view.viewId().equals(scope.viewId()))
                .findFirst()
                .orElseThrow(ApplicationFormCollectionServiceImpl::unavailable)
            : null;
    var source = inverse == null ? anchor : relations.describeTarget(inverse.source());
    var relationId = inverse == null ? scope.viewId() : inverse.sourceRelationId();
    var definition =
        source.document().relations().stream()
            .filter(
                relation ->
                    relation.sourceField() == null && relation.relationId().equals(relationId))
            .findFirst()
            .orElseThrow(ApplicationFormCollectionServiceImpl::unavailable);
    var fields = contexts.forForm(source).fieldPermissions();
    if (!FormCollectionPermissions.readable(source, relationId, fields)) {
      throw unavailable();
    }
    if (inverse != null
        && source.relationBindings().stream()
            .noneMatch(
                binding ->
                    binding.relationId().equals(relationId)
                        && binding.targetPageId().equals(anchor.pageId())
                        && binding.targetResourceCode().equals(anchor.resourceCode())
                        && binding.targetPhysicalModelId().equals(anchor.physicalModelId()))) {
      throw unavailable();
    }
    return new Owned(
        anchor,
        source,
        definition,
        inverse,
        FormCollectionPermissions.writable(source, relationId, fields)
            && forms.capabilities(source).canEdit());
  }

  private PublishedApplicationForm target(Owned owned) {
    var target = relations.resolve(owned.source(), owned.definition().relationId()).target();
    if (owned.reverse() != null
        && (!owned.anchor().pageId().equals(target.pageId())
            || !owned.anchor().resourceCode().equals(target.resourceCode()))) {
      throw unavailable();
    }
    return target;
  }

  private static boolean allowed(Owned owned, boolean link) {
    return owned.writable()
        && (owned.reverse() == null
            || (link ? owned.reverse().allowCreate() : owned.reverse().allowUnlink()));
  }

  private void requireWrite(Owned owned, boolean link) {
    if (!allowed(owned, link)) {
      throw unavailable();
    }
    forms.requireAction(owned.source(), "records.edit");
  }

  private static List<FormInverseRelationView> inverseViews(PublishedApplicationForm form) {
    return form.document().presentation() == null
        ? List.of()
        : form.document().presentation().inverseViews();
  }

  private static RecordSchemaReference schema(PublishedApplicationForm form) {
    return new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum());
  }

  private static Map<String, Object> button(
      String key, String title, String icon, int min, int max) {
    return Map.of(
        "key",
        key,
        "title",
        title,
        "icon",
        icon,
        "argumentMinSize",
        min,
        "argumentMaxSize",
        max,
        "sort",
        min,
        "danger",
        false);
  }

  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Collection relation is unavailable");
  }

  private static ApplicationFormException invalid(RuntimeException failure) {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID, "Collection operation is invalid", failure);
  }

  private record Owned(
      PublishedApplicationForm anchor,
      PublishedApplicationForm source,
      FormRelationDefinition definition,
      FormInverseRelationView reverse,
      boolean writable) {}
}
