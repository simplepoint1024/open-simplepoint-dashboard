package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a published relation's explicit entry without broadening either side's authorization.
 */
@Component
public class FormRelationTargetResolver {
  private final EntityManager entityManager;
  private final FormRequestContext context;
  private final PublishedApplicationFormResolver forms;

  /** Uses APP-owned publication storage and the current trusted actor to resolve exact targets. */
  public FormRelationTargetResolver(
      EntityManager entityManager,
      FormRequestContext context,
      PublishedApplicationFormResolver forms) {
    this.entityManager = entityManager;
    this.context = context;
    this.forms = forms;
  }

  /**
   * Resolves metadata only. Callers still enforce source-field and target-record policies, and DNA
   * separately validates storage/transaction compatibility. No caller-supplied target is accepted.
   */
  @Transactional(readOnly = true)
  public ResolvedRelation resolve(PublishedApplicationForm source, String relationId) {
    if (source == null || !context.requireActor().tenantId().equals(source.tenantId())) {
      throw unavailable();
    }
    forms.requireRead(source);
    var relation =
        source.document().relations().stream()
            .filter(candidate -> !candidate.retired() && candidate.relationId().equals(relationId))
            .findFirst()
            .orElseThrow(FormRelationTargetResolver::unavailable);
    return resolveFrozen(source, relation);
  }

  private ResolvedRelation resolveFrozen(
      PublishedApplicationForm source, FormRelationDefinition relation) {
    var frozen =
        source.relationBindings().stream()
            .filter(binding -> relation.relationId().equals(binding.relationId()))
            .findFirst()
            .orElseThrow(FormRelationTargetResolver::unavailable);
    if (!relation.target().equals(frozen.target())) {
      throw unavailable();
    }
    var resolved = resolveTarget(source.tenantId(), relation);
    if (!frozen.targetPageId().equals(resolved.target().pageId())
        || !frozen.targetResourceCode().equals(resolved.target().resourceCode())
        || !frozen.targetPhysicalModelId().equals(resolved.target().physicalModelId())) {
      throw unavailable();
    }
    return resolved;
  }

  /**
   * Unavailable targets stay absent; DNA alone decides whether a stored reference may be retained.
   */
  // Each target resolves in its own read transaction. An expected denial cannot mark a caller's
  // transaction rollback-only while this method continues collecting authorized targets.
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public List<PublishedApplicationForm> availableForWrite(PublishedApplicationForm source) {
    if (source == null || !context.requireActor().tenantId().equals(source.tenantId())) {
      throw unavailable();
    }
    forms.requireRead(source);
    var expected = source.document().relations();
    if (expected.size() != source.relationBindings().size()
        || expected.stream()
            .anyMatch(
                relation ->
                    source.relationBindings().stream()
                        .noneMatch(
                            binding ->
                                binding.relationId().equals(relation.relationId())
                                    && binding.target().equals(relation.target())))) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED,
          "The active form relation bindings are incomplete");
    }
    var targets = new java.util.ArrayList<PublishedApplicationForm>();
    for (var relation : expected) {
      if (relation.sourceField() == null || relation.retired()) {
        continue;
      }
      try {
        targets.add(resolveFrozen(source, relation).target());
      } catch (org.springframework.security.access.AccessDeniedException inaccessible) {
        // Do not manufacture target permission from the source page's policy.
      } catch (ApplicationFormException failure) {
        if (!java.util.Set.of(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
                ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
                ApplicationFormErrorCode.FORM_NOT_FOUND,
                ApplicationFormErrorCode.FORM_ARCHIVED,
                ApplicationFormErrorCode.VERSION_NOT_FOUND,
                ApplicationFormErrorCode.PAGE_NOT_FOUND,
                ApplicationFormErrorCode.PAGE_NOT_PUBLISHED,
                ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
                ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED)
            .contains(failure.errorCode())) {
          throw failure;
        }
      }
    }
    return List.copyOf(targets);
  }

  /** Resolves original snapshot entrances even if their relation has since been removed. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public List<PublishedApplicationForm> snapshotTargets(
      PublishedApplicationForm source,
      List<RecordSnapshotTarget> targets) {
    if (source == null
        || !context.requireActor().tenantId().equals(source.tenantId())
        || targets == null
        || targets.size() > 50) {
      throw unavailable();
    }
    forms.requireRead(source);
    var result = new java.util.ArrayList<PublishedApplicationForm>();
    for (var identity : targets) {
      try {
        result.add(resolveExactEntrance(source.tenantId(), identity));
      } catch (org.springframework.security.access.AccessDeniedException denied) {
        // Missing participant authority hides only the dependent historical values.
      } catch (ApplicationFormException failure) {
        if (!java.util.Set.of(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
                ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
                ApplicationFormErrorCode.FORM_NOT_FOUND,
                ApplicationFormErrorCode.FORM_ARCHIVED,
                ApplicationFormErrorCode.VERSION_NOT_FOUND,
                ApplicationFormErrorCode.PAGE_NOT_FOUND,
                ApplicationFormErrorCode.PAGE_NOT_PUBLISHED,
                ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
                ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED)
            .contains(failure.errorCode())) {
          throw failure;
        }
      }
    }
    return List.copyOf(result);
  }

  /** Revalidates a server-frozen entrance against the publisher's current page access. */
  @Transactional(readOnly = true)
  public PublishedApplicationForm requireExactEntrance(
      String tenant, RecordSnapshotTarget identity) {
    if (!tenant.equals(context.requireActor().tenantId())) {
      throw unavailable();
    }
    return resolveExactEntrance(tenant, identity);
  }

  private PublishedApplicationForm resolveExactEntrance(
      String tenant, RecordSnapshotTarget identity) {
    var matches =
        entityManager
            .createQuery(
                "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant"
                    + " and binding.applicationId = :application and binding.formId = :form"
                    + " and binding.resourceCode = :resource and binding.status = :status",
                ApplicationFormPageBindingEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("application", identity.appId())
            .setParameter("form", identity.formId())
            .setParameter("resource", identity.resourceCode())
            .setParameter("status", ApplicationFormPageBindingStatus.ACTIVE)
            .setMaxResults(2)
            .getResultList();
    if (matches.size() != 1) {
      throw unavailable();
    }
    var target = forms.resolve(identity.appId(), matches.getFirst().getPageId());
    if (!tenant.equals(target.tenantId())
        || !identity.appId().equals(target.applicationId())
        || !identity.formId().equals(target.formId())
        || !identity.modelId().equals(target.physicalModelId())
        || !identity.resourceCode().equals(target.resourceCode())) {
      throw unavailable();
    }
    return target;
  }

  /** Called once while freezing an APP version; future attempts consume the stored identities. */
  @Transactional(readOnly = true)
  public List<FormRelationBinding> freeze(ApplicationFormDocument document) {
    return freeze(document, java.util.Map.of());
  }

  @Transactional(readOnly = true)
  public List<FormRelationBinding> freeze(
      ApplicationFormDocument document, java.util.Map<String, FormRelationBinding> retired) {
    FormRelationDocumentValidator.validate(document);
    String tenant = context.requireActor().tenantId();
    return document.relations().stream()
        .sorted(java.util.Comparator.comparing(FormRelationDefinition::relationId))
        .map(
            relation -> {
              if (relation.retired()) {
                var binding = retired.get(relation.relationId());
                if (binding == null || !binding.target().equals(relation.target())) {
                  throw unavailable();
                }
                return binding;
              }
              var resolved = resolveTarget(tenant, relation);
              FormReferenceMappingPolicy.validateTarget(
                  document, relation, resolved.target().document());
              if (relation.qualification() != null) {
                FormDocumentValidator.validateRelationCondition(
                    relation.qualification().condition(), resolved.target().document().fields());
              }
              if (relation.referenceView() != null) {
                var targetFields =
                    FormReferenceViewPolicy.validate(
                        relation.referenceView(), resolved.target().document());
                FormReferenceContextPolicy.validate(document, relation, targetFields);
              }
              return new FormRelationBinding(
                  relation.relationId(),
                  relation.target(),
                  resolved.target().pageId(),
                  resolved.target().resourceCode(),
                  resolved.target().physicalModelId(),
                  FormRelationDependencies.freeze(relation, resolved.target().document()),
                  relation.referenceView() == null
                      ? null
                      : relation.referenceView().labelFieldId());
            })
        .toList();
  }

  /** Validates inverse entries before freezing a version; runtime still rechecks current grants. */
  @Transactional(readOnly = true)
  public void validateInverseViews(
      String applicationId,
      String formId,
      String physicalModelId,
      ApplicationFormDocument document) {
    if (document.presentation() == null || document.presentation().inverseViews().isEmpty()) {
      return;
    }
    String tenant = context.requireActor().tenantId();
    var authorization = org.simplepoint.core.AuthorizationContextHolder.getContext();
    if (physicalModelId == null || authorization == null) {
      throw unavailable();
    }
    for (var view : document.presentation().inverseViews()) {
      var source = describeTarget(view.source());
      var owning = resolve(source, view.sourceRelationId());
      var target = owning.target();
      if (!tenant.equals(source.tenantId())
          || !tenant.equals(target.tenantId())
          || !applicationId.equals(target.applicationId())
          || !formId.equals(target.formId())
          || !physicalModelId.equals(target.physicalModelId())) {
        throw unavailable();
      }
      var raw = DnaInvocationContextFactory.resourceFields(authorization, source.resourceCode());
      var permissions =
          FormCalculationPermissions.restrict(source, raw == null ? java.util.Map.of() : raw);
      if (owning.definition().sourceField() == null) {
        if (view.allowEdit() || view.allowDelete()
            || !FormCollectionPermissions.readable(source, view.sourceRelationId(), permissions)) {
          throw unavailable();
        }
        continue;
      }
      var selected = FormRelationSourceField.resolve(source, owning.definition().sourceField());
      if (!selected.readable(permissions)
          || view.allowUnlink() && selected.field().storageRequired()) {
        throw unavailable();
      }
    }
  }

  /** Authoring discovery uses the same exact-entry validation as frozen publication bindings. */
  @Transactional(readOnly = true)
  public PublishedApplicationForm describeTarget(FormRelationTarget identity) {
    if (identity == null) {
      throw unavailable();
    }
    return resolveTarget(context.requireActor().tenantId(), identity);
  }

  private ResolvedRelation resolveTarget(String tenant, FormRelationDefinition relation) {
    return new ResolvedRelation(relation, resolveTarget(tenant, relation.target()));
  }

  private PublishedApplicationForm resolveTarget(String tenant, FormRelationTarget identity) {
    // Look up the exact selected resource; never find an alternative page with broader grants.
    var matches =
        entityManager
            .createQuery(
                "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant and"
                    + " binding.applicationId = :application and binding.formId = :form and"
                    + " binding.resourceId = :resource and binding.status = :status",
                ApplicationFormPageBindingEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("application", identity.applicationId())
            .setParameter("form", identity.formId())
            .setParameter("resource", identity.accessResourceId())
            .setParameter("status", ApplicationFormPageBindingStatus.ACTIVE)
            .setMaxResults(2)
            .getResultList();
    if (matches.size() != 1) {
      throw unavailable();
    }
    var binding = matches.getFirst();
    // Revalidate current APP lifecycle/binding and page permission through the authoritative port.
    var target = forms.resolve(identity.applicationId(), binding.getPageId());
    if (!tenant.equals(target.tenantId())
        || !identity.applicationId().equals(target.applicationId())
        || !identity.formId().equals(target.formId())
        || !binding.getPageId().equals(target.pageId())
        || !java.util.Objects.equals(binding.getResourceCode(), target.resourceCode())) {
      throw unavailable();
    }
    return target;
  }

  private static ApplicationFormException unavailable() {
    // An inaccessible/missing/retargeted entry is deliberately indistinguishable to the caller.
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "The selected business relation entry is unavailable");
  }

  /** Detached immutable metadata; never an authorization token for subsequent record operations. */
  public record ResolvedRelation(
      FormRelationDefinition definition, PublishedApplicationForm target) {}
}
