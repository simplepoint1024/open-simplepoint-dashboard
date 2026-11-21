package org.simplepoint.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** APP-owned adapter that verifies form application identities without leaking catalog entities. */
@Component
@Transactional(readOnly = true)
public class WorkspaceApplicationAccessPort implements ApplicationAccessPort {

  private final EntityManager entityManager;

  /** Creates the catalog adapter. */
  public WorkspaceApplicationAccessPort(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public void requireApplication(final String tenantId, final String applicationId) {
    requireOwned(entityManager.find(WorkspaceApplication.class, applicationId), tenantId);
  }

  @Override
  @Transactional
  public void requireApplicationForUpdate(final String tenantId, final String applicationId) {
    requireOwned(
        entityManager.find(
            WorkspaceApplication.class, applicationId, LockModeType.PESSIMISTIC_WRITE),
        tenantId);
  }

  @Override
  @Transactional
  public void requireApplicationForFormCreation(String tenantId, String applicationId) {
    var application = entityManager.find(WorkspaceApplication.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
    requireOwned(application, tenantId);
    if ("DELETING".equals(application.getStatus())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
          "Application removal is in progress");
    }
  }

  private static void requireOwned(final WorkspaceApplication application, final String tenantId) {
    if (application == null || !tenantId.equals(application.getTenantId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
          "Application was not found in the active tenant");
    }
  }
}
