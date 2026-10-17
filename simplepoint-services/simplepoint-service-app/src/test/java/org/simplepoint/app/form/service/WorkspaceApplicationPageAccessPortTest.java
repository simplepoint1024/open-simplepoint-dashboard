package org.simplepoint.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;

class WorkspaceApplicationPageAccessPortTest {

  private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

  private EntityManager entityManager;
  private WorkspaceApplicationPageAccessPort port;
  private WorkspaceApplication application;
  private WorkspaceApplicationPage page;

  @BeforeEach
  void setUp() {
    entityManager = mock(EntityManager.class);
    port = new WorkspaceApplicationPageAccessPort(
        entityManager,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );
    application = new WorkspaceApplication();
    application.setId("app-a");
    application.setTenantId("tenant-a");
    application.setStatus("DRAFT");
    page = new WorkspaceApplicationPage();
    page.setId("page-a");
    page.setTenantId("tenant-a");
    page.setApplicationId("app-a");
    page.setName("Orders");
    page.setSortOrder(10);
    page.setStatus("DRAFT");
    when(entityManager.find(
        WorkspaceApplication.class,
        "app-a",
        LockModeType.PESSIMISTIC_WRITE
    )).thenReturn(application);
    when(entityManager.find(
        WorkspaceApplicationPage.class,
        "page-a",
        LockModeType.PESSIMISTIC_WRITE
    )).thenReturn(page);
  }

  @Test
  void atomicallyProjectsTheActiveFormAndResourceOntoTheCatalogPage() {
    port.activateBinding(new ActivateApplicationPageBinding(
        "tenant-a",
        "app-a",
        "page-a",
        "form-a",
        3,
        "resource-a",
        "app.runtime.page.a",
        "/app/runtime/app-a/page-a"
    ));

    assertThat(page.getFormId()).isEqualTo("form-a");
    assertThat(page.getFormVersion()).isEqualTo(3);
    assertThat(page.getResourceId()).isEqualTo("resource-a");
    assertThat(page.getResourceCode()).isEqualTo("app.runtime.page.a");
    assertThat(page.getStatus()).isEqualTo("PUBLISHED");
    assertThat(page.getUpdatedAt()).isEqualTo(NOW);
    assertThat(application.getStatus()).isEqualTo("PUBLISHED");
    assertThat(application.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsPageFromAnotherTenantEvenWhenItsIdExists() {
    page.setTenantId("tenant-b");

    assertThatThrownBy(() -> port.requirePageForUpdate("tenant-a", "app-a", "page-a"))
        .isInstanceOfSatisfying(ApplicationFormException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ApplicationFormErrorCode.PAGE_NOT_FOUND));
  }
}
