package org.simplepoint.plugin.dna.runtime.api.context;

import static org.simplepoint.plugin.dna.runtime.api.context.DnaPolicyValues.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded tenant, actor, and effective page policy propagated by a trusted platform service. */
public record DnaInvocationContext(
    String tenantId,
    String actorId,
    String organizationId,
    String resourceCode,
    boolean administrator,
    Set<String> resources,
    String dataScopeType,
    Set<String> departmentIds,
    boolean dataScopeIncludeSelf,
    List<String> dataScopeExpressions,
    Map<String, String> fieldPermissions,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        Map<String, DnaResourcePolicy> additionalResourcePolicies) {

  /** Legacy single-resource invocations retain their exact wire shape. */
  public DnaInvocationContext(
      String tenantId,
      String actorId,
      String organizationId,
      String resourceCode,
      boolean administrator,
      Set<String> resources,
      String dataScopeType,
      Set<String> departmentIds,
      boolean dataScopeIncludeSelf,
      List<String> dataScopeExpressions,
      Map<String, String> fieldPermissions) {
    this(
        tenantId,
        actorId,
        organizationId,
        resourceCode,
        administrator,
        resources,
        dataScopeType,
        departmentIds,
        dataScopeIncludeSelf,
        dataScopeExpressions,
        fieldPermissions,
        Map.of());
  }

  /** Validates the service boundary and defensively copies all policy collections. */
  public DnaInvocationContext {
    tenantId = required(tenantId, "tenantId", 255);
    actorId = required(actorId, "actorId", 64);
    organizationId = optional(organizationId, "organizationId", 255);
    resourceCode = optional(resourceCode, "resourceCode", 200);
    resources = normalizeSet(resources);
    validateStrings(resources, "resources", 512, 200);
    var policy =
        policy(dataScopeType, departmentIds, dataScopeExpressions, fieldPermissions, false);
    dataScopeType = policy.scope();
    departmentIds = policy.departments();
    dataScopeExpressions = policy.expressions();
    fieldPermissions = policy.fields();
    Map<String, DnaResourcePolicy> policies = new java.util.TreeMap<>();
    if (additionalResourcePolicies != null) {
      if (additionalResourcePolicies.size() > 50)
        throw new IllegalArgumentException("Related resource policy budget exceeded");
      for (var entry : additionalResourcePolicies.entrySet()) {
        String code = required(entry.getKey(), "related resource", 200);
        if (code.equals(resourceCode)
            || entry.getValue() == null
            || policies.putIfAbsent(code, entry.getValue()) != null
            || !administrator && !resources.contains(code)) {
          throw new IllegalArgumentException("Related resource policy identity is invalid");
        }
      }
    }
    if (!policies.isEmpty() && resourceCode == null)
      throw new IllegalArgumentException("Related policies require a source resource");
    long fields = fieldPermissions.size();
    long expressions = dataScopeExpressions.size();
    long departments = departmentIds.size();
    long totalText = policyText(departmentIds, dataScopeExpressions, fieldPermissions);
    long text = dataScopeExpressions.stream().mapToLong(String::length).sum();
    for (var related : policies.values()) {
      fields += related.fieldPermissions().size();
      departments += related.departmentIds().size();
      totalText +=
          policyText(
              related.departmentIds(), related.dataScopeExpressions(), related.fieldPermissions());
      expressions += related.dataScopeExpressions().size();
      text += related.dataScopeExpressions().stream().mapToLong(String::length).sum();
    }
    if (fields > 4096
        || expressions > 256
        || text > 262144
        || departments > 5000
        || totalText > 2_097_152) {
      throw new IllegalArgumentException("Related resource policy aggregate budget exceeded");
    }
    additionalResourcePolicies = java.util.Collections.unmodifiableMap(policies);
  }

  private static long policyText(
      Set<String> departments, List<String> expressions, Map<String, String> fields) {
    return departments.stream().mapToLong(String::length).sum()
        + expressions.stream().mapToLong(String::length).sum()
        + fields.entrySet().stream()
            .mapToLong(entry -> entry.getKey().length() + entry.getValue().length())
            .sum();
  }
}
