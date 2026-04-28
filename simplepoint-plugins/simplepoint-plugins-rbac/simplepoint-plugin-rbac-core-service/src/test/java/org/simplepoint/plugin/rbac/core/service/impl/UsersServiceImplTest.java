package org.simplepoint.plugin.rbac.core.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsersServiceImplTest {

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    UserRepository userRepository;

    @Mock
    DetailsProviderService detailsProviderService;

    @Mock
    UserRoleRelevanceRepository userRoleRelevanceRepository;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    RoleRepository roleRepository;

    @Mock
    PermissionChangeLogRemoteService permissionChangeLogRemoteService;

    @InjectMocks
    UsersServiceImpl service;

    @Test
    void loadUserByUsername_userFound_returnsUserDetails() {
        User user = new User();
        user.setEmail("test@example.com");
        when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("test@example.com")).thenReturn(user);

        var result = service.loadUserByUsername("test@example.com");

        assertThat(result).isEqualTo(user);
    }

    @Test
    void loadUserByUsername_userNotFound_throwsUsernameNotFoundException() {
        when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("unknown")).thenReturn(null);

        assertThatThrownBy(() -> service.loadUserByUsername("unknown"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadRolesByUserId_delegatesToRepository() {
        RoleGrantedAuthority role = new RoleGrantedAuthority("r1", "ROLE_USER");
        when(userRepository.loadRolesByUserId("default", "u1")).thenReturn(List.of(role));

        Collection<RoleGrantedAuthority> result = service.loadRolesByUserId("default", "u1");

        assertThat(result).containsExactly(role);
    }

    @Test
    void loadPermissionsInRoleIds_delegatesToRepository() {
        when(userRepository.loadPermissionsInRoleIds(List.of("r1"))).thenReturn(Set.of("perm1"));

        Collection<String> result = service.loadPermissionsInRoleIds(List.of("r1"));

        assertThat(result).containsExactly("perm1");
    }

    @Test
    void create_encodesPassword() {
        User user = new User();
        user.setPassword("rawPassword");
        when(passwordEncoder.encode("rawPassword")).thenReturn("encodedPassword");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());

        service.create(user);

        verify(passwordEncoder).encode("rawPassword");
        assertThat(user.getPassword()).isEqualTo("encodedPassword");
    }

    @Test
    void modifyById_nullPassword_doesNotEncode() {
        User user = new User();
        user.setId("u1");
        user.setPassword(null);
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        when(userRepository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));

        service.modifyById(user);

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void modifyById_bcryptPassword_keepsAsIs() {
        User user = new User();
        user.setId("u1");
        // Valid BCrypt hash
        user.setPassword("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        when(userRepository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));

        service.modifyById(user);

        verify(passwordEncoder, never()).encode(anyString());
        assertThat(user.getPassword()).isEqualTo("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
    }

    @Test
    void modifyById_plainPassword_encodesPassword() {
        User user = new User();
        user.setId("u1");
        user.setPassword("plaintext");
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        when(userRepository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("plaintext")).thenReturn("hashed");

        service.modifyById(user);

        verify(passwordEncoder).encode("plaintext");
        assertThat(user.getPassword()).isEqualTo("hashed");
    }

    @Test
    void authorized_defaultTenant_delegatesToRepository() {
        when(userRepository.authorized("default", "u1")).thenReturn(List.of("ROLE_USER"));

        Collection<String> result = service.authorized("u1");

        assertThat(result).containsExactly("ROLE_USER");
        verify(userRepository).authorized("default", "u1");
    }

    @Test
    void loadUserByPhoneOrEmail_delegatesToRepository() {
        User user = new User();
        when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("test@test.com")).thenReturn(user);

        User result = service.loadUserByPhoneOrEmail("test@test.com");

        assertThat(result).isEqualTo(user);
    }

    @Test
    void authorize_emptyRoleIds_noSave() {
        UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
        dto.setUserId("u1");
        dto.setRoleIds(Set.of());
        when(userRoleRelevanceRepository.saveAll(any())).thenReturn(Collections.emptyList());

        service.authorize(dto);

        verify(roleRepository, never()).findAllByIds(any());
        verify(userRoleRelevanceRepository).saveAll(any());
    }

    @Test
    void unauthorized_delegatesToUserRoleRelevanceRepository() {
        UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
        dto.setUserId("u1");
        Set<String> roleIds = Set.of("r1", "r2");
        dto.setRoleIds(roleIds);

        service.unauthorized(dto);

        verify(userRoleRelevanceRepository).unauthorized("default", "u1", roleIds);
    }
}
