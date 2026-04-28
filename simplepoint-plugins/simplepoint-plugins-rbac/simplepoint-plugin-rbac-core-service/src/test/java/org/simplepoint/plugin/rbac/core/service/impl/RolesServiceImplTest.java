package org.simplepoint.plugin.rbac.core.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RolesServiceImplTest {

    @Mock
    RoleRepository roleRepository;

    @Mock
    DetailsProviderService detailsProviderService;

    @Mock
    RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    PermissionChangeLogRemoteService permissionChangeLogRemoteService;

    @InjectMocks
    RolesServiceImpl service;

    private Role roleWithDefaultTenant(String id) {
        Role role = new Role();
        role.setId(id);
        role.setTenantId("default");
        return role;
    }

    @Test
    void roleSelectItems_delegatesToRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        RoleRelevanceVo vo = mock(RoleRelevanceVo.class);
        Page<RoleRelevanceVo> page = new PageImpl<>(List.of(vo));
        when(roleRepository.roleSelectItems("default", pageable)).thenReturn(page);

        Page<RoleRelevanceVo> result = service.roleSelectItems(pageable);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void authorized_roleExists_delegatesToRepository() {
        when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithDefaultTenant("r1")));
        when(roleRepository.authorized("default", "r1")).thenReturn(List.of("perm1"));

        Collection<String> result = service.authorized("r1");

        assertThat(result).containsExactly("perm1");
    }

    @Test
    void authorized_roleNotFound_throwsIllegalArgument() {
        when(roleRepository.findById("r999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorized("r999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorize_createsRelevanceAndSaves() {
        when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithDefaultTenant("r1")));
        RolePermissionsRelevanceDto dto = new RolePermissionsRelevanceDto();
        dto.setRoleId("r1");
        dto.setPermissionAuthority(Set.of("perm1", "perm2"));
        RolePermissionsRelevance saved = new RolePermissionsRelevance();
        when(rolePermissionsRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));

        Collection<RolePermissionsRelevance> result = service.authorize(dto);

        assertThat(result).contains(saved);
        verify(rolePermissionsRelevanceRepository).saveAll(any());
    }

    @Test
    void unauthorized_delegatesToRepository() {
        when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithDefaultTenant("r1")));
        Set<String> perms = Set.of("perm1");

        service.unauthorized("r1", perms);

        verify(roleRepository).unauthorized("default", "r1", perms);
    }
}
