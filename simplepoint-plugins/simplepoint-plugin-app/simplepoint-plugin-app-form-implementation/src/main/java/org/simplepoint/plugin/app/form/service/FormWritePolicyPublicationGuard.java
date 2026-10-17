package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;

/** Call under the catalog/page and form locks before superseding any pending target publication. */
final class FormWritePolicyPublicationGuard {

  private FormWritePolicyPublicationGuard() {
  }

  static void requireAvailable(final EntityManager entityManager, final String tenantId, final String formId,
      final String pageId, final String bindingId) {
    var pending = entityManager.createQuery(
            "select p.bindingId from ApplicationFormWritePolicyPublicationEntity p where p.tenantId = :tenant "
                + "and (p.formId = :form or p.pageId = :page or p.formId in "
                + "(select b.formId from ApplicationFormPageBindingEntity b where b.tenantId = :tenant "
                + "and b.pageId = :page and b.status = :pageActive)) and p.status not in :terminal", String.class)
        .setParameter("tenant", tenantId).setParameter("form", formId).setParameter("page", pageId)
        .setParameter("terminal", List.of(FormWritePolicyPublicationStatus.ACTIVE, FormWritePolicyPublicationStatus.CANCELLED))
        .setParameter("pageActive", ApplicationFormPageBindingStatus.ACTIVE).getResultList();
    if (pending.stream().anyMatch(id -> !id.equals(bindingId))) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "An earlier form policy publication must be resumed before publishing another page or version");
    }
  }
}
