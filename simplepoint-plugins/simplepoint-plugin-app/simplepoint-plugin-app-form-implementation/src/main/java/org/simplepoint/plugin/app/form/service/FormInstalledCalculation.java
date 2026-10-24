package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

/** Resolves the installed calculation contract at an already observed form admission version. */
final class FormInstalledCalculation {
  private FormInstalledCalculation() {
  }

  static boolean at(final EntityManager entityManager, final ObjectMapper mapper, final String tenantId,
      final String formId, final long admissionVersion) {
    var previous = entityManager.createQuery("select p.commandJson from ApplicationFormWritePolicyPublicationEntity p "
            + "where p.tenantId = :tenant and p.formId = :form and p.status = :active and p.targetVersion <= :current "
            + "order by p.targetVersion desc", String.class)
        .setParameter("tenant", tenantId).setParameter("form", formId)
        .setParameter("active", FormWritePolicyPublicationStatus.ACTIVE).setParameter("current", admissionVersion)
        .setMaxResults(1).getResultStream().findFirst();
    if (previous.isEmpty()) {
      return false;
    }
    try {
      return mapper.readValue(previous.get(), RecordWritePolicyPublicationCommand.class).calculation() != null;
    } catch (JsonProcessingException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, "Stored form policy is invalid", failure);
    }
  }
}
