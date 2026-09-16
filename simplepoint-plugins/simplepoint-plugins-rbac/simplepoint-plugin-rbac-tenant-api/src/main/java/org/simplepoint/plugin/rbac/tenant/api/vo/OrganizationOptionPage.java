package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.List;

/**
 * Count-free page contract for organization selectors.
 */
public record OrganizationOptionPage(
    List<OrganizationOptionVo> content,
    int number,
    int size,
    boolean hasNext
) {

  /**
   * Creates an immutable option page.
   */
  public OrganizationOptionPage {
    content = content == null ? List.of() : List.copyOf(content);
  }
}
