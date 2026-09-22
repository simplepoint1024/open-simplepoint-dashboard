package org.simplepoint.plugin.ai.runtime.service.support;

import java.util.Map;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Shared SQL-query defaults; client conditions never replace runtime ownership restrictions. */
public final class AiRuntimeTableQuery {
  private AiRuntimeTableQuery() {}

  /** Stable row identity is required before exposing a management list or its pagination totals. */
  public static void requireIdentity(Class<?> model) {
    if (!QueryCapabilities.readable(model, "id")) {
      throw new org.springframework.security.access.AccessDeniedException("缺少运行资源标识查看权限");
    }
  }

  /** Keeps system and tenant resources separate, including the system's NULL tenant. */
  public static Map<String, String> scope(ScopeAssignment scope) {
    return Map.of(
        "deletedAt",
        "is:null",
        "scopeType",
        "equals:" + scope.scopeType().name(),
        "tenantId",
        scope.tenantId() == null ? "is:null" : "equals:" + scope.tenantId());
  }

  /** Preserves newest-first defaults without sorting on a restricted field. */
  public static Pageable page(Class<?> model, Pageable page) {
    if (page.isPaged()
        && page.getSort().isUnsorted()
        && QueryCapabilities.readable(model, "createdAt")) {
      return PageRequest.of(
          page.getPageNumber(), page.getPageSize(), Sort.by("createdAt").descending());
    }
    return page;
  }
}
