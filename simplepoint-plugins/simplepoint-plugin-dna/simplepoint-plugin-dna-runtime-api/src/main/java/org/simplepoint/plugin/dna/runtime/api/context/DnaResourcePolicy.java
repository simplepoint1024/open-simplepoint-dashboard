package org.simplepoint.plugin.dna.runtime.api.context;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Effective policy for an additional, explicitly selected resource in one trusted invocation. */
public record DnaResourcePolicy(
    String contractVersion,
    String dataScopeType,
    Set<String> departmentIds,
    boolean dataScopeIncludeSelf,
    List<String> dataScopeExpressions,
    Map<String, String> fieldPermissions) {
  public DnaResourcePolicy {
    if (!"1".equals(contractVersion))
      throw new IllegalArgumentException("Unsupported resource policy contract");
    var policy =
        DnaPolicyValues.policy(
            dataScopeType, departmentIds, dataScopeExpressions, fieldPermissions, true);
    dataScopeType = policy.scope();
    departmentIds = policy.departments();
    dataScopeExpressions = policy.expressions();
    fieldPermissions = policy.fields();
  }
}
