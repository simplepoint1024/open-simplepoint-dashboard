package org.simplepoint.plugin.rbac.tenant.api.model;

import java.util.List;

public record ManagedNavigationDirectoryPage(
    List<ManagedNavigationDirectory> content, long totalElements) {
  public ManagedNavigationDirectoryPage {
    content = List.copyOf(content);
  }
}
