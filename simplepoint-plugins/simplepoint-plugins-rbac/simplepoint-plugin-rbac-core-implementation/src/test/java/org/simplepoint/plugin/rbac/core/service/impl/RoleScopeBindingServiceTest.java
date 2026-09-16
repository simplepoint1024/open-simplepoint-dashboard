package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleScopeBindingRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.RoleScopeBinding;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

class RoleScopeBindingServiceTest {
  final RoleScopeBindingRepository bindings = mock(RoleScopeBindingRepository.class);
  final RoleResourceGrantRepository grants = mock(RoleResourceGrantRepository.class);
  final ScopePolicyValidator validator = mock(ScopePolicyValidator.class);
  final RoleScopeBindingService service = new RoleScopeBindingService(bindings, grants, validator);

  RoleScopeAssignmentVo assignment() {
    RoleScopeAssignmentVo assignment = new RoleScopeAssignmentVo();
    assignment.setRoleId("r1");
    return assignment;
  }

  RoleResourceGrant grant(String tenant, String scope) {
    RoleResourceGrant grant = new RoleResourceGrant();
    grant.setTenantId(tenant);
    grant.setRoleId("r1");
    grant.setDataScopeId(scope);
    return grant;
  }

  @Test
  void savesScopeForRoleWithoutAnyResourceGrant() {
    var assignment = assignment();
    assignment.setDataScopeId("data");
    service.save("t1", assignment);
    verify(bindings).save(argThat(binding -> binding.getRoleId().equals("r1")
        && binding.getTenantId().equals("t1") && binding.getDataScopeId().equals("data")));
    verify(grants, never()).saveAll(any());
  }

  @Test
  void explicitEmptyBindingDoesNotFallBackToLegacyScope() {
    RoleScopeBinding binding = new RoleScopeBinding();
    binding.setRevision(2L);
    when(bindings.findByTenantIdAndRoleId("t1", "r1")).thenReturn(Optional.of(binding));
    var result = service.read("t1", "r1");
    assertThat(result.getDataScopeId()).isNull();
    assertThat(result.getRevision()).isEqualTo(2);
    verifyNoInteractions(grants);
  }

  @Test
  void conflictingLegacyBindingsRequireExplicitConfirmation() {
    when(grants.findByRoleIdIn(List.of("r1"))).thenReturn(List.of(grant("t1", "a"), grant("t1", "b"), grant("t2", "foreign")));
    assertThat(service.read("t1", "r1").getLegacyDataScopeIds()).containsExactly("a", "b");
    assertThatThrownBy(() -> service.save("t1", assignment())).isInstanceOf(IllegalArgumentException.class);
    verify(bindings, never()).save(any());
    var assignment = assignment();
    assignment.setConfirmLegacyReplacement(true);
    service.save("t1", assignment);
    verify(bindings).save(any());
  }

  @Test
  void staleRevisionCannotOverwriteBinding() {
    RoleScopeBinding binding = new RoleScopeBinding();
    binding.setId("binding");
    binding.setRevision(2L);
    when(bindings.findByTenantIdAndRoleId("t1", "r1")).thenReturn(Optional.of(binding));
    var assignment = assignment();
    assignment.setRevision(1L);
    assertThatThrownBy(() -> service.save("t1", assignment)).isInstanceOf(OptimisticLockingFailureException.class);
    verify(bindings, never()).save(any());
  }

  @Test
  void policyValidatorRejectsForeignDeletedAndUnknownPolicies() {
    DataScopeRepository data = mock(DataScopeRepository.class);
    FieldScopeRepository fields = mock(FieldScopeRepository.class);
    ScopePolicyValidator realValidator = new ScopePolicyValidator(data, fields);
    assertThatThrownBy(() -> realValidator.validate("t1", "foreign", null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> realValidator.validate("t1", null, "deleted")).isInstanceOf(AccessDeniedException.class);
    verify(data).findByIdAndTenantIdAndDeletedAtIsNull("foreign", "t1");
    verify(fields).findByIdAndTenantIdAndDeletedAtIsNull("deleted", "t1");
  }
}
