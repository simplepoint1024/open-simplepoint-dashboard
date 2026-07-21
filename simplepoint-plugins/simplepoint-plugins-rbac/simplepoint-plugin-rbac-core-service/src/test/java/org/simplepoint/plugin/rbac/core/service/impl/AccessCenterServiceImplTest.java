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
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterResourceNodeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.service.ResourceService;

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

  @Mock
  ResourceService resourceService;

  @InjectMocks
  AccessCenterServiceImpl service;

  @Test
  void saveRoleAuthorization_appliesOnlyPermissionDiffAndScope() {
    Role role = new Role();
    role.setId("role1");
    role.setTenantId("tenant1");
    role.setRoleName("Manager");
    when(roleService.authorized("role1"))
        .thenReturn(List.of("resources.view", "resources.old"), List.of("resources.view", "resources.new"));
    when(roleRepository.findById("role1")).thenReturn(Optional.of(role));
    when(userRoleRelevanceRepository.countByTenantIdAndRoleId("tenant1", "role1")).thenReturn(2L);
    when(userRoleRelevanceRepository.findUsersByTenantIdAndRoleId("tenant1", "role1")).thenReturn(List.of());

    AccessCenterRoleAuthorizationDto dto = new AccessCenterRoleAuthorizationDto();
    dto.setRoleId("role1");
    dto.setResourceCodes(Set.of("resources.view", "resources.new"));
    dto.setDataScopeId("data1");
    dto.setFieldScopeId("field1");

    AccessCenterRoleDetailVo result = service.saveRoleAuthorization(dto);

    ArgumentCaptor<RoleResourceGrantDto> authorizeCaptor =
        ArgumentCaptor.forClass(RoleResourceGrantDto.class);
    verify(roleService).authorize(authorizeCaptor.capture());
    assertThat(authorizeCaptor.getValue().getResourceCodes()).containsExactlyInAnyOrder("resources.new");
    assertThat(authorizeCaptor.getValue().getDataScopeId()).isEqualTo("data1");
    assertThat(authorizeCaptor.getValue().getFieldScopeId()).isEqualTo("field1");
    verify(roleService).unauthorized("role1", Set.of("resources.old"));
    verify(roleService).updateScopeAssignment(any());
    assertThat(result.getAuthorizedResources()).containsExactly("resources.view", "resources.new");
    assertThat(result.getAssignedUserCount()).isEqualTo(2L);
  }

  @Test
  void resourceTree_buildsUnifiedResourceTree() {
    Resource root = new Resource();
    root.setId("resource-root");
    root.setCode("system");
    root.setLabel("系统管理");
    root.setType(ResourceType.GROUP);
    root.setGrantable(false);
    root.setSort(0);

    Resource rolePage = new Resource();
    rolePage.setId("resource-role");
    rolePage.setParentId("resource-root");
    rolePage.setCode("roles.view");
    rolePage.setLabel("角色管理");
    rolePage.setType(ResourceType.PAGE);
    rolePage.setGrantable(true);
    rolePage.setPath("/system/role");
    rolePage.setSort(1);

    Resource roleEdit = new Resource();
    roleEdit.setId("resource-role-edit");
    roleEdit.setParentId("resource-role");
    roleEdit.setCode("roles.edit");
    roleEdit.setLabel("编辑角色");
    roleEdit.setType(ResourceType.ACTION);
    roleEdit.setGrantable(true);
    roleEdit.setSort(2);

    Resource usersView = new Resource();
    usersView.setId("resource-users");
    usersView.setCode("users.view");
    usersView.setLabel("用户查看");
    usersView.setType(ResourceType.API);
    usersView.setGrantable(true);
    usersView.setSort(10);

    when(roleService.authorized("role1")).thenReturn(List.of("roles.view"));
    when(resourceService.findAllAccessible()).thenReturn(List.of(root, rolePage, roleEdit, usersView));

    List<AccessCenterResourceNodeVo> tree = service.resourceTree("role1");

    assertThat(tree).hasSize(2);
    AccessCenterResourceNodeVo rootNode = tree.get(0);
    assertThat(rootNode.getLabel()).isEqualTo("系统管理");
    assertThat(rootNode.isPartial()).isTrue();
    assertThat(rootNode.getResourceCodes()).containsExactlyInAnyOrder("roles.view", "roles.edit");

    AccessCenterResourceNodeVo roleNode = rootNode.getChildren().get(0);
    assertThat(roleNode.getType()).isEqualTo("PAGE");
    assertThat(roleNode.isPartial()).isTrue();
    assertThat(roleNode.getResourceCodes()).containsExactlyInAnyOrder("roles.view", "roles.edit");
    assertThat(roleNode.getChildren().get(0).getResourceCode()).isEqualTo("roles.edit");
    assertThat(roleNode.getChildren().get(0).isChecked()).isFalse();

    AccessCenterResourceNodeVo usersNode = tree.get(1);
    assertThat(usersNode.getType()).isEqualTo("API");
    assertThat(usersNode.getResourceCodes()).containsExactly("users.view");
  }
}
