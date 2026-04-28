package org.simplepoint.plugin.rbac.core.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionsServiceImplTest {

    @Mock
    PermissionsRepository repository;

    @Mock
    DetailsProviderService detailsProviderService;

    @InjectMocks
    PermissionsServiceImpl service;

    @Test
    void permissionItems_withNullAuthorities_returnsEmptyList() {
        Collection<PermissionsRelevanceVo> result = service.permissionItems((Collection<String>) null);
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void permissionItems_withEmptyAuthorities_returnsEmptyList() {
        Collection<PermissionsRelevanceVo> result = service.permissionItems(Set.of());
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void permissionItems_withAuthorities_delegatesToRepository() {
        Set<String> authorities = Set.of("perm1", "perm2");
        PermissionsRelevanceVo vo = mock(PermissionsRelevanceVo.class);
        when(repository.permissionItems(authorities)).thenReturn(List.of(vo));

        Collection<PermissionsRelevanceVo> result = service.permissionItems(authorities);

        assertThat(result).containsExactly(vo);
        verify(repository).permissionItems(authorities);
    }

    @Test
    void permissionItems_pageable_asAdmin_returnsAllPermissions() {
        Pageable pageable = PageRequest.of(0, 10);
        PermissionsRelevanceVo vo = mock(PermissionsRelevanceVo.class);
        Page<PermissionsRelevanceVo> page = new PageImpl<>(List.of(vo));

        try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
            AuthorizationContext ctx = new AuthorizationContext();
            ctx.setIsAdministrator(true);
            mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);
            when(repository.permissionItemsAll(pageable)).thenReturn(page);

            Page<PermissionsRelevanceVo> result = service.permissionItems(pageable);

            assertThat(result).isEqualTo(page);
            verify(repository).permissionItemsAll(pageable);
            verify(repository, never()).permissionItems(any(Pageable.class), any());
        }
    }

    @Test
    void permissionItems_pageable_asNonAdmin_returnsFilteredPermissions() {
        Pageable pageable = PageRequest.of(0, 10);
        PermissionsRelevanceVo vo = mock(PermissionsRelevanceVo.class);
        Page<PermissionsRelevanceVo> page = new PageImpl<>(List.of(vo));
        Collection<String> contextPermissions = Set.of("perm1");

        try (MockedStatic<AuthorizationContextHolder> mockedStatic = mockStatic(AuthorizationContextHolder.class)) {
            AuthorizationContext ctx = new AuthorizationContext();
            ctx.setIsAdministrator(false);
            ctx.setPermissions(contextPermissions);
            mockedStatic.when(AuthorizationContextHolder::getContext).thenReturn(ctx);
            when(repository.permissionItems(pageable, contextPermissions)).thenReturn(page);

            Page<PermissionsRelevanceVo> result = service.permissionItems(pageable);

            assertThat(result).isEqualTo(page);
            verify(repository).permissionItems(pageable, contextPermissions);
            verify(repository, never()).permissionItemsAll(any());
        }
    }
}
