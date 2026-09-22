package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.query.InvalidQueryException;

/** Read-only draft selection, never a grant of access to the requested identifiers. */
public record OrganizationSelection(List<String> ids, boolean selected, Map<String, String> filters) {
  public static final int MAX_IDS = 2000;

  /** Bounds the draft payload separately from the public filter-tree IN budget. */
  public OrganizationSelection {
    if (ids == null || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
      throw new InvalidQueryException("组织选择草稿最多包含 2000 个有效标识");
    }
    ids = ids.stream().distinct().toList();
    filters = filters == null ? Map.of() : Map.copyOf(filters);
  }
}
