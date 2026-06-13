package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RolesServiceImplTest {

  @Mock
  RoleRepository roleRepository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  JsonSchemaDetailsService jsonSchemaDetailsService;

  @Mock
  JsonSchemaGenerator jsonSchemaGenerator;

  @Mock
  RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  @Mock
  TenantRepository tenantRepository;

  @Mock
  PermissionVersionRefreshService permissionVersionRefreshService;

  @Mock
  PermissionChangeLogRemoteService permissionChangeLogRemoteService;

  @InjectMocks
  RolesServiceImpl service;

  MockedStatic<AuthorizationContextHolder> authorizationContextHolder;

  @BeforeEach
  void setUp() {
    ObjectNode schema = new ObjectMapper().createObjectNode();
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("tenantId");
    properties.putObject("authority");
    properties.putObject("roleName");
    lenient().when(roleRepository.getDomainClass()).thenReturn((Class) Role.class);
    lenient().when(detailsProviderService.getDialect(JsonSchemaDetailsService.class))
        .thenReturn(jsonSchemaDetailsService);
    lenient().when(detailsProviderService.getDialect(JsonSchemaGenerator.class))
        .thenReturn(jsonSchemaGenerator);
    lenient().when(jsonSchemaGenerator.generateSchema(Role.class)).thenReturn(schema);
    lenient().when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(List.of());
    authorizationContextHolder = mockStatic(AuthorizationContextHolder.class);
    authorizationContextHolder.when(AuthorizationContextHolder::getContext).thenReturn(tenantAdminContext());
  }

  @AfterEach
  void tearDown() {
    authorizationContextHolder.close();
  }

  private Role roleWithTenant(String id) {
    Role role = new Role();
    role.setId(id);
    role.setTenantId("tenant1");
    return role;
  }

  private static AuthorizationContext tenantAdminContext() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(true);
    ctx.setPermissions(Set.of());
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant1"));
    return ctx;
  }

  @Test
  void roleSelectItems_delegatesToRepository() {
    Pageable pageable = PageRequest.of(0, 10);
    RoleRelevanceVo vo = mock(RoleRelevanceVo.class);
    Page<RoleRelevanceVo> page = new PageImpl<>(List.of(vo));
    when(roleRepository.roleSelectItems("tenant1", pageable)).thenReturn(page);

    Page<RoleRelevanceVo> result = service.roleSelectItems(pageable);

    assertThat(result).isEqualTo(page);
  }

  @Test
  void authorized_roleExists_delegatesToRepository() {
    when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithTenant("r1")));
    when(roleRepository.authorized("tenant1", "r1")).thenReturn(List.of("perm1"));

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
    when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithTenant("r1")));
    RolePermissionsRelevanceDto dto = new RolePermissionsRelevanceDto();
    dto.setRoleId("r1");
    dto.setPermissionAuthority(Set.of("perm1", "perm2"));
    RolePermissionsRelevance saved = new RolePermissionsRelevance();
    when(rolePermissionsRelevanceRepository.saveAll(any())).thenReturn(List.of(saved));

    Collection<RolePermissionsRelevance> result = service.authorize(dto);

    assertThat(result).contains(saved);
    ArgumentCaptor<Iterable<RolePermissionsRelevance>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(rolePermissionsRelevanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(RolePermissionsRelevance::getTenantId)
        .containsOnly("tenant1");
  }

  @Test
  void unauthorized_delegatesToRepository() {
    when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithTenant("r1")));
    Set<String> perms = Set.of("perm1");

    service.unauthorized("r1", perms);

    verify(roleRepository).unauthorized("tenant1", "r1", perms);
  }

  @Test
  void create_tenantRole_refreshesPermissionVersion() {
    Role role = new Role();
    role.setTenantId("tenant1");
    when(roleRepository.save(role)).thenReturn(role);

    Role result = service.create(role);

    assertThat(result).isSameAs(role);
    verify(permissionVersionRefreshService).refreshTenants(Set.of("tenant1"));
  }

  @Test
  void modifyById_tenantRole_refreshesPermissionVersion() {
    Role current = new Role();
    current.setId("r1");
    current.setTenantId("tenant1");
    Role update = new Role();
    update.setId("r1");
    update.setTenantId("tenant1");
    when(roleRepository.findById("r1")).thenReturn(Optional.of(current));
    when(roleRepository.updateById(any(Role.class))).thenReturn(update);

    Role result = service.modifyById(update);

    assertThat(result).isSameAs(update);
    verify(permissionVersionRefreshService).refreshTenants(Set.of("tenant1"));
  }

  @Test
  void removeByIds_tenantRoles_refreshesAffectedTenants() {
    Role role = new Role();
    role.setId("r1");
    role.setTenantId("tenant1");
    when(roleRepository.findAllByIds(Set.of("r1"))).thenReturn(List.of(role));

    service.removeByIds(Set.of("r1"));

    verify(roleRepository).deleteByIds(Set.of("r1"));
    verify(permissionVersionRefreshService).refreshTenants(Set.of("tenant1"));
  }
}
