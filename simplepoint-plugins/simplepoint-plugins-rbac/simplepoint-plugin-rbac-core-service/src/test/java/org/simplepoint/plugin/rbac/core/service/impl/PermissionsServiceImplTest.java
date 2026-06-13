package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.repository.ResourcesRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PermissionsServiceImplTest {

  @Mock
  PermissionsRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  JsonSchemaDetailsService jsonSchemaDetailsService;

  @Mock
  JsonSchemaGenerator jsonSchemaGenerator;

  @Mock
  RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  @Mock
  FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;

  @Mock
  ResourcesRelevanceRepository resourcesRelevanceRepository;

  @Mock
  PermissionVersionRefreshService permissionVersionRefreshService;

  @InjectMocks
  PermissionsServiceImpl service;

  @BeforeEach
  void setUp() {
    ObjectNode schema = new ObjectMapper().createObjectNode();
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("authority");
    properties.putObject("name");
    properties.putObject("resource");
    properties.putObject("description");
    properties.putObject("type");
    lenient().when(repository.getDomainClass()).thenReturn((Class) Permissions.class);
    lenient().when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(jsonSchemaDetailsService);
    lenient().when(detailsProviderService.getDialect(JsonSchemaGenerator.class)).thenReturn(jsonSchemaGenerator);
    lenient().when(jsonSchemaGenerator.generateSchema(Permissions.class)).thenReturn(schema);
    lenient().when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(List.of());
  }

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

  @Test
  void modifyById_authorityChanged_updatesRelationsAndRefreshesAffectedTenants() {
    Permissions current = new Permissions();
    current.setId("p1");
    current.setAuthority("perm.old");
    Permissions update = new Permissions();
    update.setId("p1");
    update.setAuthority("perm.new");
    when(repository.findById("p1")).thenReturn(java.util.Optional.of(current));
    when(repository.updateById(any(Permissions.class))).thenReturn(update);
    when(permissionVersionRefreshService.findAffectedTenantIdsByPermissionAuthorities(Set.of("perm.old")))
        .thenReturn(Set.of("tenant1", "tenant2"));

    Permissions result = service.modifyById(update);

    assertThat(result).isSameAs(update);
    verify(rolePermissionsRelevanceRepository).updatePermissionAuthority("perm.old", "perm.new");
    verify(featurePermissionRelevanceRepository).updatePermissionAuthority("perm.old", "perm.new");
    verify(resourcesRelevanceRepository).updatePermissionAuthority("perm.old", "perm.new");
    verify(permissionVersionRefreshService).refreshTenants(Set.of("tenant1", "tenant2"));
  }

  @Test
  void removeByIds_publicPermissionRefreshesAllTenantsAndDeletesRelations() {
    Permissions permission = new Permissions();
    permission.setId("p1");
    permission.setAuthority("perm.public");
    when(repository.findAllByIds(Set.of("p1"))).thenReturn(List.of(permission));
    when(permissionVersionRefreshService.findAffectedTenantIdsByPermissionAuthorities(Set.of("perm.public")))
        .thenReturn(Set.of("tenant1", "tenant2"));

    service.removeByIds(Set.of("p1"));

    verify(rolePermissionsRelevanceRepository).deleteAllByPermissionAuthorities(Set.of("perm.public"));
    verify(featurePermissionRelevanceRepository).deleteAllByPermissionAuthorities(Set.of("perm.public"));
    verify(resourcesRelevanceRepository).deleteAllByPermissionAuthorities(Set.of("perm.public"));
    verify(repository).deleteByIds(Set.of("p1"));
    verify(permissionVersionRefreshService).refreshTenants(Set.of("tenant1", "tenant2"));
  }
}
