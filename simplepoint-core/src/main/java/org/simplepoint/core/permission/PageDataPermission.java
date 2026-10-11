package org.simplepoint.core.permission;

import org.simplepoint.core.AuthorizationContextHolder;
import org.springframework.security.access.AccessDeniedException;

/** Row guard for custom actions after tenant checks and before I/O or mutation. */
public final class PageDataPermission {
  private PageDataPermission() {}

  /** Applies only an explicitly selected page rule; domain/tenant checks remain mandatory. */
  public static void requireAccessible(Object entity) {
    if (!allows(entity)) throw new AccessDeniedException("当前数据范围不允许访问该记录");
  }

  public static boolean allows(Object entity) {
    if (entity == null) return false;
    var context = AuthorizationContextHolder.getContext();
    var scope = context == null ? null : context.activeDataScope();
    if (scope == null) return true;
    Class<?> type = org.simplepoint.core.jackson.FieldPermissionPolicy.resourceType(entity.getClass());
    var mapping = type.getAnnotation(PermissionModel.class);
    return scope.allows(mapping == null ? "createOrgDeptId" : mapping.department(),
        mapping == null ? "createdBy" : mapping.owner(), context, name -> {
          var field = org.springframework.util.ReflectionUtils.findField(type, name);
          if (field == null) throw new AccessDeniedException("数据范围字段映射无效");
          org.springframework.util.ReflectionUtils.makeAccessible(field);
          return org.springframework.util.ReflectionUtils.getField(field, entity);
        });
  }
}
