package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.*;
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
    return catalog(null);
  }
  public Map<String, PermissionPageDescriptor> catalog(String resourceCode) {
    Map<String, PermissionPageDescriptor> result = new TreeMap<>();
    RegisteredPermissionPages.forEach(em, resourceCode, RegisteredPermissionPages.Projection.EXPRESSION, row -> {
      String code = (String) row[0];
      var descriptor = PermissionPageDescriptor.fromJson((String) row[1]);
      if (descriptor != null && descriptor.data() && !descriptor.commonProperties().isEmpty()) result.put(code, descriptor);
    });
    return result;
  }

  public record Validation(String expression, QueryDocument.Node ast) {}
  public Validation validate(String resourceCode, String expression, QueryDocument.Node ast) {
    RegisteredPermissionPages.caller();
    if (resourceCode == null || resourceCode.isBlank()) throw new InvalidPermissionPolicyException("请选择已授权的目标页面");
    var descriptor = catalog(resourceCode).get(resourceCode);
    if (descriptor == null) throw new InvalidPermissionPolicyException("页面不存在、未授权或尚未注册数据权限元数据");
    String text = ast == null ? expression : PermissionExpression.format(ast);
    var parsed = PermissionExpression.validate(text, descriptor.commonProperties());
    return new Validation(PermissionExpression.format(parsed), parsed);
  }
}
