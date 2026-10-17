package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormPublicationRequirementsCompiler.Version;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads an immutable requirement plan; policy preparation later rechecks the observed page set and epoch. */
@Service
public class FormPublicationImpactService {

  private final EntityManager entityManager;
  private final FormDocumentCodec codec;
  private final FormRequestContext context;

  /** Depends only on APP metadata and the shared requirement compiler, never on target rows. */
  public FormPublicationImpactService(final EntityManager entityManager, final FormDocumentCodec codec,
      final FormRequestContext context) {
    this.entityManager = entityManager;
    this.codec = codec;
    this.context = context;
  }

  /** Includes the candidate and every surviving active historical page, under the effective governing definition. */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public FormWritePolicyImpact requirements(final BindingWork work) {
    if (work == null || !context.requireActor().tenantId().equals(work.tenantId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "The publication is unavailable in the current tenant");
    }
    var binding = entityManager.find(ApplicationFormPageBindingEntity.class, work.bindingId());
    if (binding == null || !work.tenantId().equals(binding.getTenantId()) || !work.applicationId().equals(binding.getApplicationId())
        || !work.formId().equals(binding.getFormId()) || !work.pageId().equals(binding.getPageId())
        || work.formVersion() != binding.getFormVersion() || work.generation() != binding.getGeneration()
        || binding.getStatus() != ApplicationFormPageBindingStatus.ACTIVATING || work.executionToken() == null
        || !work.executionToken().equals(binding.getExecutionToken())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The publication attempt is no longer current");
    }
    var form = entityManager.createQuery("from ApplicationFormEntity f where f.tenantId = :tenant "
            + "and f.applicationId = :app and f.id = :form", ApplicationFormEntity.class)
        .setParameter("tenant", work.tenantId()).setParameter("app", work.applicationId()).setParameter("form", work.formId())
        .getResultStream().findFirst().orElseThrow(() -> failure(ApplicationFormErrorCode.FORM_NOT_FOUND, "The form is unavailable"));
    if (!work.physicalModelId().equals(form.getPhysicalModelId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT, "The publication physical identity has changed");
    }
    int governingVersion = Math.max(work.formVersion(), form.getPublishedVersion() == null ? 0 : form.getPublishedVersion());
    var budget = new FormActiveDefinitionBudget();
    final ApplicationFormDocument governing = version(work, governingVersion, budget).document();
    List<ApplicationFormPageBindingEntity> active = entityManager.createQuery(
            "from ApplicationFormPageBindingEntity b where b.tenantId = :tenant and b.applicationId = :app "
                + "and b.formId = :form and b.status = :active order by b.id", ApplicationFormPageBindingEntity.class)
        .setParameter("tenant", work.tenantId()).setParameter("app", work.applicationId()).setParameter("form", work.formId())
        .setParameter("active", ApplicationFormPageBindingStatus.ACTIVE).setMaxResults(1001).getResultList();
    if (active.size() > 1000) {
      throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, "The publication exceeds its active page budget");
    }
    var observedBindings = new HashSet<String>();
    var effectiveVersions = new TreeSet<Integer>();
    effectiveVersions.add(work.formVersion());
    for (var page : active) {
      observedBindings.add(page.getId());
      if (!work.pageId().equals(page.getPageId())) {
        effectiveVersions.add(page.getFormVersion());
      }
    }
    Iterable<Version> pages = () -> effectiveVersions.stream().map(number -> version(work, number, budget)).iterator();
    var plan = FormPublicationRequirementsCompiler.compile(work.physicalModelId(), work.applicationId(), work.formId(), pages, governing);
    return new FormWritePolicyImpact(form.getWritePolicyVersion(), governingVersion, observedBindings, plan.checks(), plan.valueChecks());
  }

  private Version version(final BindingWork work, final int number, final FormActiveDefinitionBudget budget) {
    // Project scalar snapshots so the persistence context does not retain up to 1,000 full documents.
    var rows = entityManager.createQuery("select v.physicalModelVersion, v.storageChecksum, v.documentJson "
            + "from ApplicationFormVersionEntity v where v.tenantId = :tenant "
            + "and v.formId = :form and v.versionNumber = :version", Object[].class)
        .setParameter("tenant", work.tenantId()).setParameter("form", work.formId()).setParameter("version", number)
        .getResultList();
    if (rows.isEmpty()) {
      throw failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "An effective page definition is unavailable");
    }
    Object[] row = rows.getFirst();
    if (row[0] == null || row[1] == null) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "An effective page schema is not ready");
    }
    budget.include(number, (String) row[2]);
    return new Version(new RecordSchemaReference((Integer) row[0], (String) row[1]), codec.decode((String) row[2]));
  }

  private static ApplicationFormException failure(final ApplicationFormErrorCode code, final String message) {
    return new ApplicationFormException(code, message);
  }
}
