package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.AccessCenterRoleAuthorizationDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Role;

@ExtendWith(MockitoExtension.class)
class AccessCenterServiceImplTest {

  @Mock
  RoleService roleService;

  @Mock
  RoleRepository roleRepository;

  @Mock
  UserRoleRelevanceRepository userRoleRelevanceRepository;

  @Mock
  DataScopeRepository dataScopeRepository;

  @Mock
  FieldScopeRepository fieldScopeRepository;

  @InjectMocks
  AccessCenterServiceImpl service;

  @Test
  void saveRoleAuthorization_appliesOnlyPermissionDiffAndScope() {
    Role role = new Role();
    role.setId("role1");
    role.setTenantId("tenant1");
    role.setRoleName("Manager");
    when(roleService.authorized("role1")).thenReturn(List.of("perm.view", "perm.old"), List.of("perm.view", "perm.new"));
    when(roleRepository.findById("role1")).thenReturn(Optional.of(role));
    when(userRoleRelevanceRepository.countByTenantIdAndRoleId("tenant1", "role1")).thenReturn(2L);
    when(userRoleRelevanceRepository.findUsersByTenantIdAndRoleId("tenant1", "role1")).thenReturn(List.of());

    AccessCenterRoleAuthorizationDto dto = new AccessCenterRoleAuthorizationDto();
    dto.setRoleId("role1");
    dto.setPermissionAuthorities(Set.of("perm.view", "perm.new"));
    dto.setDataScopeId("data1");
    dto.setFieldScopeId("field1");

    AccessCenterRoleDetailVo result = service.saveRoleAuthorization(dto);

    ArgumentCaptor<RolePermissionsRelevanceDto> authorizeCaptor =
        ArgumentCaptor.forClass(RolePermissionsRelevanceDto.class);
    verify(roleService).authorize(authorizeCaptor.capture());
    assertThat(authorizeCaptor.getValue().getPermissionAuthority()).containsExactlyInAnyOrder("perm.new");
    assertThat(authorizeCaptor.getValue().getDataScopeId()).isEqualTo("data1");
    assertThat(authorizeCaptor.getValue().getFieldScopeId()).isEqualTo("field1");
    verify(roleService).unauthorized("role1", Set.of("perm.old"));
    verify(roleService).updateScopeAssignment(any());
    assertThat(result.getAuthorizedPermissions()).containsExactly("perm.view", "perm.new");
    assertThat(result.getAssignedUserCount()).isEqualTo(2L);
  }
}
