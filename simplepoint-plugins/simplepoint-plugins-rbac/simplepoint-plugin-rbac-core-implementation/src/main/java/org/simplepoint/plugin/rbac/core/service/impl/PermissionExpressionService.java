package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.TreeMap;
import org.simplepoint.core.permission.InvalidPermissionPolicyException;
import org.simplepoint.core.permission.PermissionExpression;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.core.query.QueryDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses only trusted registered page contracts; the client cannot supply field/SQL mappings. */
@Service
@Transactional(readOnly = true)
public class PermissionExpressionService {
  private final EntityManager em;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog tenantResources;

  /** Creates the registered expression metadata service. */
  public PermissionExpressionService(EntityManager em) {
    this.em = em;
  }

  /** Lists data expression contracts for held pages. */
  public Map<String, PermissionPageDescriptor> catalog() {
    return catalog(null);
  }

  /** Reads only the requested held page when a code is supplied. */
  public Map<String, PermissionPageDescriptor> catalog(String resourceCode) {
    Map<String, PermissionPageDescriptor> result = new TreeMap<>();
    RegisteredPermissionPages.forEach(
        em,
        tenantResources,
        resourceCode,
        RegisteredPermissionPages.Projection.EXPRESSION,
        row -> {
          String code = (String) row[0];
          var descriptor = PermissionPageDescriptor.fromJson((String) row[1]);
          if (descriptor != null && descriptor.data() && !descriptor.commonProperties().isEmpty()) {
            result.put(code, descriptor);
          }
        });
    return result;
  }

  /** Canonical expression text and validated syntax tree. */
  public record Validation(String expression, QueryDocument.Node ast) {}

  /** Validates text or a visual syntax tree against the current page contract. */
  public Validation validate(String resourceCode, String expression, QueryDocument.Node ast) {
    RegisteredPermissionPages.caller();
    if (resourceCode == null || resourceCode.isBlank()) {
      throw new InvalidPermissionPolicyException("请选择已授权的目标页面");
    }
    var descriptor = catalog(resourceCode).get(resourceCode);
    if (descriptor == null) {
      throw new InvalidPermissionPolicyException("页面不存在、未授权或尚未注册数据权限元数据");
    }
    String text = ast == null ? expression : PermissionExpression.format(ast);
    var parsed = PermissionExpression.validate(text, descriptor.commonProperties());
    return new Validation(PermissionExpression.format(parsed), parsed);
  }
}
