package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.util.List;

/** Bounded directory page independent of Spring Data and persistence entities. */
public record UserDirectoryPage(List<UserDirectoryEntry> content, long totalElements) {

  /** Freezes the returned page and rejects inconsistent or unbounded provider output. */
  public UserDirectoryPage {
    content = List.copyOf(content);
    if (content.size() > 50 || totalElements < 0) {
      throw new IllegalArgumentException("Invalid user directory page");
    }
  }
}
