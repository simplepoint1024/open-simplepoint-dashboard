package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.service.ResourceService;
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
  RoleResourceGrantRepository roleResourceGrantRepository;

  @Mock
  TenantRepository tenantRepository;

  @Mock
  ResourceAuthorizationVersionService resourceAuthorizationVersionService;

  @Mock
  org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService resourceGrantLogRemoteService;

  @Mock
  ResourceService resourceService;

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
    lenient().when(resourceService.findAllByCodes(any())).thenAnswer(invocation -> {
      Collection<String> codes = invocation.getArgument(0);
      return codes.stream().map(code -> {
        Resource resource = new Resource();
        resource.setCode(code);
        resource.setGrantable(true);
        resource.setDisabled(false);
        return resource;
      }).toList();
    });
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
    ctx.setUserId("user1");
    ctx.setContextId("context1");
    ctx.setResources(Set.of());
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
    when(roleRepository.authorized("tenant1", "r1")).thenReturn(List.of("resources.view"));

    Collection<String> result = service.authorized("r1");

    assertThat(result).containsExactly("resources.view");
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
    RoleResourceGrantDto dto = new RoleResourceGrantDto();
    dto.setRoleId("r1");
    dto.setResourceCodes(Set.of("resources.view", "resources.edit"));
    RoleResourceGrant saved = new RoleResourceGrant();
    when(roleResourceGrantRepository.saveAll(any())).thenReturn(List.of(saved));

    Collection<RoleResourceGrant> result = service.authorize(dto);

    assertThat(result).contains(saved);
    ArgumentCaptor<Iterable<RoleResourceGrant>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(roleResourceGrantRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(RoleResourceGrant::getTenantId)
        .containsOnly("tenant1");
  }

  @Test
  void authorize_ignoresResourceGrantLogFailure() {
    when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithTenant("r1")));
    RoleResourceGrantDto dto = new RoleResourceGrantDto();
    dto.setRoleId("r1");
    dto.setResourceCodes(Set.of("resources.view"));
    RoleResourceGrant saved = new RoleResourceGrant();
    when(roleResourceGrantRepository.saveAll(any())).thenReturn(List.of(saved));
    doThrow(new RuntimeException("audit unavailable"))
        .when(resourceGrantLogRemoteService).record(any());

    Collection<RoleResourceGrant> result = service.authorize(dto);

    assertThat(result).contains(saved);
    verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
    verify(resourceGrantLogRemoteService).record(any());
  }

  @Test
  void unauthorized_delegatesToRepository() {
    when(roleRepository.findById("r1")).thenReturn(Optional.of(roleWithTenant("r1")));
    Set<String> resources = Set.of("resources.view");

    service.unauthorized("r1", resources);

    verify(roleRepository).unauthorized("tenant1", "r1", resources);
  }

  @Test
  void create_tenantRole_refreshesAuthorizationVersion() {
    Role role = new Role();
    role.setTenantId("tenant1");
    when(roleRepository.save(role)).thenReturn(role);

    Role result = service.create(role);

    assertThat(result).isSameAs(role);
    verify(resourceAuthorizationVersionService).refreshTenants(Set.of("tenant1"));
  }

  @Test
  void create_tenantManager_overridesForgedTenantId() {
    AuthorizationContext context = tenantUserContext("SELF");
    authorizationContextHolder.when(AuthorizationContextHolder::getContext).thenReturn(context);
    Role role = new Role();
    role.setTenantId("another-tenant");
    when(roleRepository.save(role)).thenReturn(role);

    service.create(role);

    assertThat(role.getTenantId()).isEqualTo("tenant1");
  }

  @Test
  void limit_securityMetadata_doesNotApplyBusinessRowScope() {
    AuthorizationContext context = tenantUserContext("SELF");
    authorizationContextHolder.when(AuthorizationContextHolder::getContext).thenReturn(context);
    Role role = roleWithTenant("new-role");
    Pageable pageable = PageRequest.of(0, 20);
    when(roleRepository.limit(Map.of("deletedAt", "is:null"), pageable)).thenAnswer(invocation -> {
      assertThat(DataScopeContext.get()).isNotNull();
      assertThat(DataScopeContext.get().isAllData()).isTrue();
      return new PageImpl<>(List.of(role), pageable, 1);
    });

    Page<Role> result = service.limit(Map.of(), pageable);

    assertThat(result.getContent()).containsExactly(role);
    assertThat(DataScopeContext.get()).isNull();
  }

  @Test
  void modifyById_tenantRole_refreshesAuthorizationVersion() {
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
    verify(resourceAuthorizationVersionService).refreshTenants(Set.of("tenant1"));
  }

  @Test
  void removeByIds_tenantRoles_refreshesAffectedTenants() {
    Role role = new Role();
    role.setId("r1");
    role.setTenantId("tenant1");
    when(roleRepository.findAllByIds(Set.of("r1"))).thenReturn(List.of(role));

    service.removeByIds(Set.of("r1"));

    verify(roleRepository).deleteByIds(List.of("r1"));
    verify(resourceAuthorizationVersionService).refreshTenants(Set.of("tenant1"));
  }

  private static AuthorizationContext tenantUserContext(String dataScopeType) {
    AuthorizationContext context = new AuthorizationContext();
    context.setIsAdministrator(false);
    context.setUserId("user1");
    context.setContextId("context1");
    context.setResources(Set.of());
    context.setAttributes(Map.of("X-Tenant-Id", "tenant1"));
    context.setDataScopeType(dataScopeType);
    return context;
  }
}
