package org.simplepoint.app.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceApplicationCatalogTest {
  private final EntityManager em = mock(EntityManager.class);
  private final WorkspaceApplicationCatalog catalog = new WorkspaceApplicationCatalog(em);

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void createPersistsOneDraftApplicationAndExactlyOneMainPageInCurrentTenant() {
    tenantContext("tenant-a");
    List<Object> persisted = new ArrayList<>();
    org.mockito.Mockito.doAnswer(invocation -> {
      persisted.add(invocation.getArgument(0));
      return null;
    }).when(em).persist(any());

    var detail = catalog.create(new WorkspaceApplicationCatalog.CreateApplication(
        "  Expense App  ", "Costs", "  Home  "));

    assertThat(persisted).hasSize(2);
    WorkspaceApplication app = (WorkspaceApplication) persisted.get(0);
    WorkspaceApplicationPage main = (WorkspaceApplicationPage) persisted.get(1);
    assertThat(app.getTenantId()).isEqualTo("tenant-a");
    assertThat(app.getName()).isEqualTo("Expense App");
    assertThat(app.getStatus()).isEqualTo("DRAFT");
    assertThat(main.getTenantId()).isEqualTo("tenant-a");
    assertThat(main.getApplicationId()).isEqualTo(app.getId());
    assertThat(main.getId()).isEqualTo(app.getMainPageId());
    assertThat(main.getKind()).isEqualTo("MAIN");
    assertThat(main.getName()).isEqualTo("Home");
    assertThat(detail.pages()).hasSize(1);
    assertThat(detail.pages().get(0).resourceRegistered()).isFalse();
  }

  @Test
  void detailDoesNotExposeApplicationsFromAnotherTenant() {
    tenantContext("tenant-a");
    WorkspaceApplication other = new WorkspaceApplication();
    other.setId("app-1");
    other.setTenantId("tenant-b");
    when(em.find(WorkspaceApplication.class, "app-1")).thenReturn(other);

    assertThatThrownBy(() -> catalog.detail("app-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    verify(em).find(WorkspaceApplication.class, "app-1");
  }

  @Test
  void applicationCatalogRequiresTenantScope() {
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);

    assertThatThrownBy(() -> catalog.create(new WorkspaceApplicationCatalog.CreateApplication(
        "App", null, "Home"))).isInstanceOf(AccessDeniedException.class);
  }

  private void tenantContext(String tenantId) {
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    context.setUserId("user-1");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
