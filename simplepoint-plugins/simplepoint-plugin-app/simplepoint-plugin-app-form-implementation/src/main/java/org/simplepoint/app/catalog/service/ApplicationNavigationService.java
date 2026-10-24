package org.simplepoint.app.catalog.service;

import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.springframework.stereotype.Service;

/** Network failures leave a durable pending command; the same configuration safely resumes it. */
@Service
public class ApplicationNavigationService {
  private final ApplicationNavigationState state;
  private final ManagedTenantResourceService resources;

  public ApplicationNavigationService(
      ApplicationNavigationState state, ManagedTenantResourceService resources) {
    this.state = state;
    this.resources = resources;
  }

  public ApplicationNavigationState.View view(String id) {
    return state.view(id);
  }

  public ApplicationNavigationState.View update(
      String id, ApplicationNavigationState.Update command) {
    var work = state.begin(id, command);
    try {
      resources.applyNavigation(work.command());
    } catch (RuntimeException failure) {
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode
              .NAVIGATION_SYNC_PENDING,
          "Navigation synchronization is pending",
          failure);
    }
    return state.complete(work);
  }

  public ApplicationNavigationState.View cancel(
      String id, ApplicationNavigationState.Update command) {
    var work = state.begin(id, command);
    return resources.cancelNavigation(work.command())
        ? state.cancelled(work)
        : state.complete(work);
  }

  public record Directory(
      String id,
      String code,
      String name,
      org.simplepoint.api.locale.LocalizedText nameLocalization) {}

  public record DirectoryPage(java.util.List<Directory> content, long totalElements) {}

  public DirectoryPage directories(String id, String keyword, int offset, int limit) {
    state.view(
        id); // Revalidate tenant and actual application editing capability before remote reads.
    var page =
        resources.navigationDirectories(
            ApplicationNavigationState.tenant(),
            ApplicationNavigationState.root(id),
            keyword,
            offset,
            limit);
    return new DirectoryPage(
        page.content().stream()
            .map(row -> new Directory(row.id(), row.code(), row.name(), row.nameLocalization()))
            .toList(),
        page.totalElements());
  }
}
