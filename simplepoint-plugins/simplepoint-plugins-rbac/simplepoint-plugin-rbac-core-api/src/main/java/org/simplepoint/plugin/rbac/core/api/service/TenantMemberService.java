package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Tenant-scoped member queries and guarded membership changes. */
public interface TenantMemberService {
  /** Returns current-tenant management capabilities. */
  java.util.Map<String, Boolean> capabilities();

  /** Lists members without user filters for existing callers. */
  default Page<TenantMemberView> list(Pageable pageable) {
    return list(java.util.Map.of(), pageable);
  }

  /** Applies the declared read-model query before pagination and counting. */
  Page<TenantMemberView> list(java.util.Map<String, String> filters, Pageable pageable);

  /** Counts and pages members of one organization and all its descendants in the database. */
  Page<TenantMemberView> organizationMembers(String orgId, String keyword, Pageable pageable);

  /** Queries members within organization subtree and data-permission boundaries. */
  Page<TenantMemberView> queryOrganizationMembers(
      String orgId, java.util.Map<String, String> filters, Pageable pageable);

  /** Searches current-tenant members available for assignment to the selected organization. */
  Page<TenantMemberView> organizationCandidates(String orgId, String keyword, Pageable pageable);

  /** Queries eligible candidates using the same typed table contract. */
  Page<TenantMemberView> queryOrganizationCandidates(
      String orgId, java.util.Map<String, String> filters, Pageable pageable);

  /** Adds an existing account to the current tenant. */
  void add(TenantMemberCommand command);

  /** Updates membership with optimistic revision validation. */
  void update(String userId, TenantMemberCommand command);

  /** Detaches an organization membership, retaining tenant membership and tenant roles. */
  void removeFromOrganization(
      String userId,
      org.simplepoint.plugin.rbac.core.api.pojo.command.OrganizationMemberRemoval command);

  /** Removes a member without deleting the global account. */
  void remove(String userId);
}
