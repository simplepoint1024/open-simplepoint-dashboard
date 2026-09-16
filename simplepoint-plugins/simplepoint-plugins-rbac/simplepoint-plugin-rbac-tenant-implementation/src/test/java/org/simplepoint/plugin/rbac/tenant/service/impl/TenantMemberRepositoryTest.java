package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.mockito.Mockito.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.repository.JpaTenantUserRelevanceRepository;

class TenantMemberRepositoryTest {
  @Test
  void legacyRemovalDeletesOnlyMatchingTenantRolesBeforeMembership() {
    var repository = mock(JpaTenantUserRelevanceRepository.class, CALLS_REAL_METHODS);
    repository.unauthorized("tenant-1", Set.of("member-1"));
    var order = inOrder(repository);
    order.verify(repository).deleteMemberRoles("tenant-1", Set.of("member-1"));
    order.verify(repository).deleteMemberships("tenant-1", Set.of("member-1"));
    verify(repository, never()).deleteAll();
    verify(repository, never()).deleteAllByTenantIds(any());
  }

  @Test
  void emptyRemovalDoesNotIssueUnboundedDeletes() {
    var repository = mock(JpaTenantUserRelevanceRepository.class, CALLS_REAL_METHODS);
    repository.unauthorized("tenant-1", Set.of());
    verify(repository, never()).deleteMemberRoles(any(), any());
    verify(repository, never()).deleteMemberships(any(), any());
  }
}
