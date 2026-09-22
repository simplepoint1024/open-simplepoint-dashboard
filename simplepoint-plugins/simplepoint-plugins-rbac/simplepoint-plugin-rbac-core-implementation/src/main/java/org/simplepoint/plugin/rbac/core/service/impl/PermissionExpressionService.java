package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.*;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.permission.*;
import org.simplepoint.core.query.QueryDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses only trusted registered page contracts; the client cannot supply field/SQL mappings. */
@Service
@Transactional(readOnly = true)
public class PermissionExpressionService {
  private final EntityManager em;
  public PermissionExpressionService(EntityManager em) { this.em = em; }

  public Map<String, PermissionPageDescriptor> catalog() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null) throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    Map<String, PermissionPageDescriptor> result = new TreeMap<>();
    em.createQuery("select r.code,r.permissionMetadata from Resource r where r.deletedAt is null"
        + " and r.disabled=false and r.type='PAGE' and r.permissionMetadata is not null", Object[].class)
        .getResultList().forEach(row -> {
          String code = (String) row[0];
          if (context.getResources() == null || !context.getResources().contains(code)) return;
          var descriptor = PermissionPageDescriptor.fromJson((String) row[1]);
          if (descriptor.data() && !descriptor.commonProperties().isEmpty()) result.put(code, descriptor);
        });
    return result;
  }

  public record Validation(String expression, QueryDocument.Node ast) {}
  public Validation validate(String resourceCode, String expression, QueryDocument.Node ast) {
    var descriptor = catalog().get(resourceCode);
    if (descriptor == null) throw new InvalidPermissionPolicyException("页面不存在、未授权或尚未注册数据权限元数据");
    String text = ast == null ? expression : PermissionExpression.format(ast);
    var parsed = PermissionExpression.validate(text, descriptor.commonProperties());
    return new Validation(PermissionExpression.format(parsed), parsed);
  }
}
