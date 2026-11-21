package org.simplepoint.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageBindingTarget;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageTarget;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** APP-owned adapter for page locking and active runtime binding changes. */
@Component
@Transactional
public class WorkspaceApplicationPageAccessPort implements ApplicationPageAccessPort {

  private final EntityManager entityManager;
  private final Clock clock;

  /** Creates the adapter with the shared publication clock. */
  public WorkspaceApplicationPageAccessPort(final EntityManager entityManager, final Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  @Override
  public ApplicationPageTarget requirePageForUpdate(
      final String tenantId, final String applicationId, final String pageId) {
    WorkspaceApplication application = requireApplication(tenantId, applicationId);
    if ("DELETING".equals(application.getStatus())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
          "Application removal is in progress");
    }
    WorkspaceApplicationPage page = requirePage(tenantId, applicationId, pageId);
    if ("OFFLINING".equals(page.getStatus())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "Wait for resource offline participants to complete before publishing");
    }
    if (application.getNavigationPending() != null) throw new ApplicationFormException(
        ApplicationFormErrorCode.PAGE_BINDING_CONFLICT, "Navigation update must complete before publication");
    String root = null, parent = null; Long revision = null;
    if (application.getNavigationDefinition() != null) {
      try {
        var navigation = new com.fasterxml.jackson.databind.ObjectMapper().readValue(application.getNavigationDefinition(),
            org.simplepoint.plugin.app.form.api.model.ApplicationNavigation.class);
        root = org.simplepoint.app.catalog.service.ApplicationNavigationState.root(applicationId);
        revision = application.getNavigationRevision();
        var placement = navigation.pages().stream().filter(item -> item.id().equals(pageId)).findFirst().orElse(null);
        parent = org.simplepoint.app.catalog.service.ApplicationNavigationState.key(applicationId, placement == null ? null : placement.directoryId());
      } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("Invalid application navigation", failure);}
    }
    return new ApplicationPageTarget(
        page.getId(),
        page.getName(),
        page.getDescription(),
        page.getSortOrder(),
        application.isNavigationGroup() ? application.getName() : null,
        new org.simplepoint.plugin.app.form.api.model.PageResourcePresentation(page.getNameLocalization(), application.getNameLocalization(), root, revision, parent),
        page.getPublicationGeneration());
  }

  @Override
  public void activateBinding(final ActivateApplicationPageBinding binding) {
    if (binding == null
        || binding.formVersion() < 1
        || blank(binding.resourceId())
        || blank(binding.resourceCode())
        || blank(binding.runtimePath())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT, "The active page binding is incomplete");
    }
    final WorkspaceApplication application =
        requireApplication(binding.tenantId(), binding.applicationId());
    WorkspaceApplicationPage page =
        requirePage(binding.tenantId(), binding.applicationId(), binding.pageId());
    Instant timestamp = clock.instant();
    page.setFormId(binding.formId());
    page.setFormVersion(binding.formVersion());
    page.setResourceId(binding.resourceId());
    page.setResourceCode(binding.resourceCode());
    page.setStatus("PUBLISHED");
    page.setUpdatedAt(timestamp);
    application.setStatus("PUBLISHED");
    application.setUpdatedAt(timestamp);
  }

  @Override
  public void offlineBinding(
      String tenantId, String applicationId, String pageId, boolean completed) {
    WorkspaceApplication application = requireApplication(tenantId, applicationId);
    WorkspaceApplicationPage page = requirePage(tenantId, applicationId, pageId);
    if (!"PUBLISHED".equals(page.getStatus())
        && !"OFFLINING".equals(page.getStatus())
        && !"OFFLINE".equals(page.getStatus())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "Only a published resource can go offline");
    }
    Instant changedAt = clock.instant();
    page.setStatus(completed ? "OFFLINE" : "OFFLINING");
    page.setUpdatedAt(changedAt);
    var siblingStates = entityManager.createQuery(
            "select distinct p.status from WorkspaceApplicationPage p where p.tenantId = :tenant"
                + " and p.applicationId = :app and p.id <> :page and p.status in ('PUBLISHED', 'OFFLINING')",
            String.class)
        .setParameter("tenant", tenantId).setParameter("app", applicationId)
        .setParameter("page", pageId).getResultList();
    if (siblingStates.contains("PUBLISHED")) {
      application.setStatus("PUBLISHED");
    } else {
      application.setStatus(completed && siblingStates.isEmpty() ? "OFFLINE" : "OFFLINING");
    }
    application.setUpdatedAt(changedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public ApplicationPageBindingTarget requireActiveBinding(
      final String tenantId, final String applicationId, final String pageId) {
    WorkspaceApplicationPage page = entityManager.find(WorkspaceApplicationPage.class, pageId);
    if (page == null
        || !tenantId.equals(page.getTenantId())
        || !applicationId.equals(page.getApplicationId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_NOT_FOUND, "Page was not found in the active application");
    }
    if (!"PUBLISHED".equals(page.getStatus())
        || blank(page.getFormId())
        || page.getFormVersion() == null
        || page.getFormVersion() < 1
        || blank(page.getResourceCode())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_NOT_PUBLISHED,
          "Page does not have an active form publication");
    }
    return new ApplicationPageBindingTarget(
        page.getId(),
        page.getName(),
        page.getFormId(),
        page.getFormVersion(),
        page.getResourceCode());
  }

  private WorkspaceApplication requireApplication(
      final String tenantId, final String applicationId) {
    WorkspaceApplication application =
        entityManager.find(
            WorkspaceApplication.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
    if (application == null || !tenantId.equals(application.getTenantId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
          "Application was not found in the active tenant");
    }
    return application;
  }

  private WorkspaceApplicationPage requirePage(
      final String tenantId, final String applicationId, final String pageId) {
    WorkspaceApplicationPage page =
        entityManager.find(WorkspaceApplicationPage.class, pageId, LockModeType.PESSIMISTIC_WRITE);
    if (page == null
        || !tenantId.equals(page.getTenantId())
        || !applicationId.equals(page.getApplicationId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_NOT_FOUND, "Page was not found in the active application");
    }
    return page;
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }
}
