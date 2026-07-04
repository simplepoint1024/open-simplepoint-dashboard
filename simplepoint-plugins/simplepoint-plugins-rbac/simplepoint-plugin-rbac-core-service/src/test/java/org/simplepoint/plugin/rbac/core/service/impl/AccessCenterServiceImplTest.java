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
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterResourceNodeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.Role;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class AccessCenterServiceImplTest {

  @Mock
  RoleService roleService;

  @Mock
  PermissionsService permissionsService;

  @Mock
  RoleRepository roleRepository;

  @Mock
  UserRoleRelevanceRepository userRoleRelevanceRepository;

  @Mock
  DataScopeRepository dataScopeRepository;

  @Mock
  FieldScopeRepository fieldScopeRepository;

  @Mock
  MenuRepository menuRepository;

  @Mock
  MenuFeatureRelevanceRepository menuFeatureRelevanceRepository;

  @Mock
  FeatureService featureService;

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

  @Test
  void resourceTree_buildsMenuFeaturePermissionTreeAndUnclassifiedGroup() {
    Menu root = new Menu();
    root.setId("menu-root");
    root.setLabel("系统管理");
    root.setSort(0);
    Menu roleMenu = new Menu();
    roleMenu.setId("menu-role");
    roleMenu.setParent("menu-root");
    roleMenu.setLabel("角色管理");
    roleMenu.setPath("/system/role");
    roleMenu.setSort(1);
    Feature roleFeature = new Feature();
    roleFeature.setCode("role-management");
    roleFeature.setName("角色维护");
    roleFeature.setSort(0);
    PermissionsRelevanceVo roleView = new PermissionsRelevanceVo(
        "p1",
        "角色查看",
        "roles.view",
        "查看角色",
        0
    );
    PermissionsRelevanceVo roleEdit = new PermissionsRelevanceVo(
        "p2",
        "角色编辑",
        "roles.edit",
        "编辑角色",
        1
    );
    PermissionsRelevanceVo usersView = new PermissionsRelevanceVo(
        "p3",
        "用户查看",
        "users.view",
        "查看用户",
        0
    );
    when(roleService.authorized("role1")).thenReturn(List.of("roles.view"));
    when(permissionsService.permissionItems(org.springframework.data.domain.Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(roleView, roleEdit, usersView)));
    when(menuRepository.loadAll()).thenReturn(List.of(root, roleMenu));
    when(menuFeatureRelevanceRepository.authorized("menu-root")).thenReturn(List.of());
    when(menuFeatureRelevanceRepository.authorized("menu-role")).thenReturn(List.of("role-management"));
    when(featureService.findAllByCodes(Set.of("role-management"))).thenReturn(List.of(roleFeature));
    when(featureService.authorizedPermissions("role-management")).thenReturn(List.of("roles.view", "roles.edit"));

    List<AccessCenterResourceNodeVo> tree = service.resourceTree("role1");

    assertThat(tree).hasSize(2);
    AccessCenterResourceNodeVo rootNode = tree.get(0);
    assertThat(rootNode.getLabel()).isEqualTo("系统管理");
    assertThat(rootNode.isPartial()).isTrue();
    assertThat(rootNode.getPermissionAuthorities()).containsExactlyInAnyOrder("roles.view", "roles.edit");
    AccessCenterResourceNodeVo featureNode = rootNode.getChildren().get(0).getChildren().get(0);
    assertThat(featureNode.getType()).isEqualTo("FEATURE");
    assertThat(featureNode.getPermissionAuthorities()).containsExactlyInAnyOrder("roles.view", "roles.edit");
    assertThat(featureNode.getChildren())
        .extracting(AccessCenterResourceNodeVo::getPermissionAuthority)
        .containsExactly("roles.view", "roles.edit");
    assertThat(featureNode.getChildren().get(0).isChecked()).isTrue();
    assertThat(featureNode.getChildren().get(1).isChecked()).isFalse();
    AccessCenterResourceNodeVo unclassified = tree.get(1);
    assertThat(unclassified.getType()).isEqualTo("GROUP");
    assertThat(unclassified.getPermissionAuthorities()).containsExactly("users.view");
  }
}
