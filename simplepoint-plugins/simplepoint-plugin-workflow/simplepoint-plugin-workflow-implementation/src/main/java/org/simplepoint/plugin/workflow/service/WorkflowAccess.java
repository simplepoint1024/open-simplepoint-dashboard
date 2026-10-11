package org.simplepoint.plugin.workflow.service;

import java.util.List;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.workflow.api.WorkflowException;

/** Shared domain authorization boundary for workflow commands and read projections. */
final class WorkflowAccess {
  private WorkflowAccess() {}

  static String tenantId() {
    var context = AuthorizationContextHolder.getContext();
    String tenant = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (context == null
        || context.getScopeType() != AuthorizationScopeType.TENANT
        || tenant == null
        || tenant.isBlank()) {
      throw forbidden("请切换到组织工作空间");
    }
    return tenant.trim();
  }

  static String userId() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
      throw forbidden("无法识别当前用户");
    }
    return context.getUserId();
  }

  static List<String> roles() {
    var context = AuthorizationContextHolder.getContext();
    return context == null || context.getRoles() == null
        ? List.of()
        : context.getRoles().stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
  }

  static boolean allowed(String authority) {
    var context = AuthorizationContextHolder.getContext();
    return context != null
        && (Boolean.TRUE.equals(context.getIsAdministrator())
            || context.getResources() != null && context.getResources().contains(authority));
  }

  static void requireAuthority(String authority) {
    if (!allowed(authority)) {
      throw forbidden("当前用户没有操作权限");
    }
  }

  static boolean canViewPersonalMatters() {
    return allowed(
            org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes
                .INSTANCES_STARTED_VIEW)
        || allowed(
            org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes.INSTANCES_VIEW);
  }

  private static WorkflowException forbidden(String message) {
    return new WorkflowException(WorkflowException.Code.FORBIDDEN, message);
  }
}
