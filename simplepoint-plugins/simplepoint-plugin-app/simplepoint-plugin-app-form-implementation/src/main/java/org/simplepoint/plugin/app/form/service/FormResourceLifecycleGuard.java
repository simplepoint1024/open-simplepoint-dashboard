package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

/** Checks the durable fence while holding the same form lock used by submission acceptance. */
final class FormResourceLifecycleGuard {
  private FormResourceLifecycleGuard() {}

  static void requireAvailable(EntityManager entityManager, String tenant, String page) {
    Long pending = entityManager.createQuery("select count(o) from ApplicationResourceOfflineEntity o "
        + "where o.tenantId = :tenant and o.pageId = :page and (o.state = 'OFFLINING' or "
        + "(o.state = 'OFFLINE' and not exists (select b.id from ApplicationFormPageBindingEntity b "
        + "where b.tenantId = :tenant and b.pageId = :page and b.status = "
        + "org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus.ACTIVE "
        + "and b.generation > o.generation)))", Long.class)
        .setParameter("tenant", tenant).setParameter("page", page).getSingleResult();
    if (pending != null && pending > 0) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PAGE_NOT_PUBLISHED,
          "Page resource is offline or being taken offline");
    }
  }
}
