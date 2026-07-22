package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.command.UserProfileUpdateCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

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
  TenantUserRelevanceRepository tenantUserRelevanceRepository;

  @Mock
  ResourceAuthorizationVersionService resourceAuthorizationVersionService;

  @Mock
  RoleRepository roleRepository;

  @Mock
  ResourceGrantLogRemoteService resourceGrantLogRemoteService;

  @InjectMocks
  UsersServiceImpl service;

  // ── loadUserByUsername ────────────────────────────────────────────────────

  @Test
  void loadUserByUsername_userFound_returnsUserDetails() {
    User user = new User();
    user.setId("u1");
    user.setEmail("test@example.com");
    Tenant personalTenant = new Tenant();
    personalTenant.setId("tenant-u1");
    when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("test@example.com")).thenReturn(user);
    when(tenantRepository.findPersonalTenantByOwnerId("u1")).thenReturn(Optional.of(personalTenant));
    when(tenantUserRelevanceRepository.authorized("tenant-u1")).thenReturn(List.of("u1"));

    var result = service.loadUserByUsername("test@example.com");

    assertThat(result).isEqualTo(user);
  }

  @Test
  void loadUserByUsername_missingPersonalTenant_createsTenantAndMembership() {
    User user = new User();
    user.setId("u1");
    user.setName("Tester");
    user.setEmail("test@example.com");
    Tenant savedTenant = new Tenant();
    savedTenant.setId("tenant-personal");
    when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("test@example.com")).thenReturn(user);
    when(tenantRepository.findPersonalTenantByOwnerId("u1")).thenReturn(Optional.empty());
    when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
    when(tenantUserRelevanceRepository.authorized("tenant-personal")).thenReturn(List.of());

    var result = service.loadUserByUsername("test@example.com");

    assertThat(result).isEqualTo(user);
    verify(tenantRepository).save(any(Tenant.class));
    verify(tenantUserRelevanceRepository).saveAll(any(Collection.class));
  }

  @Test
  void loadUserByUsername_userNotFound_throwsUsernameNotFoundException() {
    when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("unknown")).thenReturn(null);

    assertThatThrownBy(() -> service.loadUserByUsername("unknown"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  // ── loadRolesByUserId ─────────────────────────────────────────────────────

  @Test
  void loadRolesByUserId_delegatesToRepository() {
    RoleGrantedAuthority role = new RoleGrantedAuthority("r1", "ROLE_USER");
    when(userRepository.loadRolesByUserId("default", "u1")).thenReturn(List.of(role));

    Collection<RoleGrantedAuthority> result = service.loadRolesByUserId("default", "u1");

    assertThat(result).containsExactly(role);
  }

  @Test
  void limit_identityMetadata_doesNotApplyBusinessRowScope() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(false);
    ctx.setUserId("manager");
    ctx.setDataScopeType("SELF");
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-1"));
    User member = new User();
    member.setId("member-1");
    PageRequest pageable = PageRequest.of(0, 20);
    when(tenantUserRelevanceRepository.authorized("tenant-1")).thenReturn(List.of("member-1"));
    when(userRepository.limit(
        Map.of("id", "in:member-1", "deletedAt", "is:null"), pageable
    )).thenAnswer(invocation -> {
      assertThat(DataScopeContext.get()).isNotNull();
      assertThat(DataScopeContext.get().isAllData()).isTrue();
      return new PageImpl<>(List.of(member), pageable, 1);
    });

    try (MockedStatic<AuthorizationContextHolder> ctxMock = mockStatic(AuthorizationContextHolder.class)) {
      ctxMock.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      Page<User> result = service.limit(Map.of(), pageable);

      assertThat(result.getContent()).containsExactly(member);
      assertThat(DataScopeContext.get()).isNull();
    }
  }

  // ── loadResourcesInRoleIds ────────────────────────────────────────────────

  @Test
  void loadResourcesInRoleIds_delegatesToRepository() {
    when(userRepository.loadResourcesInRoleIds(List.of("r1"))).thenReturn(Set.of("resources.view"));

    Collection<String> result = service.loadResourcesInRoleIds(List.of("r1"));

    assertThat(result).containsExactly("resources.view");
  }

  // ── create ────────────────────────────────────────────────────────────────

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

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_nullPassword_doesNotEncode() {
    User user = new User();
    user.setId("u1");
    user.setPassword(null);
    stubExistingUserForModify(user);

    service.modifyById(user);

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void modifyById_emptyPassword_doesNotEncode() {
    User user = new User();
    user.setId("u1");
    user.setPassword("");
    stubExistingUserForModify(user);

    service.modifyById(user);

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void modifyById_bcryptPassword_keepsAsIs() {
    User user = new User();
    user.setId("u1");
    user.setPassword("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
    stubExistingUserForModify(user);

    service.modifyById(user);

    verify(passwordEncoder, never()).encode(anyString());
    assertThat(user.getPassword()).isEqualTo("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
  }

  @Test
  void modifyById_plainPassword_encodesPassword() {
    User user = new User();
    user.setId("u1");
    user.setPassword("plaintext");
    stubExistingUserForModify(user);
    when(passwordEncoder.encode("plaintext")).thenReturn("hashed");

    service.modifyById(user);

    verify(passwordEncoder).encode("plaintext");
    assertThat(user.getPassword()).isEqualTo("hashed");
  }

  @Test
  void updateCurrentProfile_updatesOnlyPersonalFields() {
    User user = new User();
    user.setId("u1");
    user.setNickname("Old");
    user.setPassword("encoded-password");
    user.setSuperAdmin(true);
    user.setTwoFactorEnabled(true);
    when(userRepository.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    when(userRepository.updateById(user)).thenReturn(user);
    UserProfileUpdateCommand command = new UserProfileUpdateCommand();
    command.setNickname(" New nickname ");
    command.setEmail("new@example.com");
    command.setPicture("/common/object-storage/images/object-1");

    User result = service.updateCurrentProfile("u1", command);

    assertThat(result.getNickname()).isEqualTo("New nickname");
    assertThat(result.getPicture()).isEqualTo("/common/object-storage/images/object-1");
    assertThat(result.getPassword()).isEqualTo("encoded-password");
    assertThat(result.getSuperAdmin()).isTrue();
    assertThat(result.getTwoFactorEnabled()).isTrue();
  }

  private void stubExistingUserForModify(User user) {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var schema = mapper.createObjectNode();
    var properties = mapper.createObjectNode();
    properties.set("password", mapper.createObjectNode());
    properties.set("picture", mapper.createObjectNode());
    schema.set("properties", properties);
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    when(userRepository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));
    when(userRepository.getDomainClass()).thenReturn(User.class);
    var schemaDetails = mock(org.simplepoint.api.security.service.JsonSchemaDetailsService.class);
    var schemaGenerator = mock(org.simplepoint.api.security.generator.JsonSchemaGenerator.class);
    when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(schemaDetails);
    when(detailsProviderService.getDialect(org.simplepoint.api.security.generator.JsonSchemaGenerator.class))
        .thenReturn(schemaGenerator);
    when(schemaGenerator.generateSchema(User.class)).thenReturn(schema);
  }

  @Test
  void updateCurrentProfile_rejectsInlineDataAvatar() {
    User user = new User();
    user.setId("u1");
    when(userRepository.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));
    UserProfileUpdateCommand command = new UserProfileUpdateCommand();
    command.setPicture("data:image/png;base64,AAAA");

    assertThatThrownBy(() -> service.updateCurrentProfile("u1", command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("头像必须使用 OSS 图片地址");
    verify(userRepository, never()).updateById(any());
  }

  // ── authorized ────────────────────────────────────────────────────────────

  @Test
  void authorized_noContext_usesPersonalTenantScope() {
    // No AuthorizationContext set — userId is null, so scope falls back to null (no personal tenant lookup possible)
    lenient().when(userRepository.authorized(null, "u1")).thenReturn(List.of("ROLE_USER"));

    service.authorized("u1");

    verify(userRepository).authorized(null, "u1");
  }

  @Test
  void authorized_withTenantContext_usesCurrentTenantScope() {
    AuthorizationContext ctx = buildCtx("admin", true, "tenant1");
    try (MockedStatic<AuthorizationContextHolder> ctxMock = mockStatic(AuthorizationContextHolder.class)) {
      ctxMock.when(AuthorizationContextHolder::getContext).thenReturn(ctx);
      when(userRepository.authorized("tenant1", "u1")).thenReturn(List.of("ROLE_ADMIN"));

      Collection<String> result = service.authorized("u1");

      assertThat(result).containsExactly("ROLE_ADMIN");
      verify(userRepository).authorized("tenant1", "u1");
    }
  }

  // ── loadUserByPhoneOrEmail ────────────────────────────────────────────────

  @Test
  void loadUserByPhoneOrEmail_delegatesToRepository() {
    User user = new User();
    when(userRoleRelevanceRepository.loadUserByPhoneOrEmail("test@test.com")).thenReturn(user);

    User result = service.loadUserByPhoneOrEmail("test@test.com");

    assertThat(result).isEqualTo(user);
  }

  @Test
  void searchPickerItems_shortKeywordDoesNotLoadUserDirectory() {
    Page<UserPickerItem> result = service.searchPickerItems("ab", PageRequest.of(0, 20));

    assertThat(result).isEmpty();
    verify(userRepository, never()).searchPickerItems(anyString(), any());
  }

  @Test
  void searchPickerItems_trimsAndDelegatesEmailOrPhonePrefix() {
    PageRequest pageable = PageRequest.of(0, 20);
    UserPickerItem item = new UserPickerItem(
        "u1", "Alice", "alice@example.com", "13800000000", null
    );
    Page<UserPickerItem> page = new PageImpl<>(List.of(item), pageable, 1);
    when(userRepository.searchPickerItems("alice@", pageable)).thenReturn(page);

    Page<UserPickerItem> result = service.searchPickerItems("  alice@  ", pageable);

    assertThat(result.getContent()).containsExactly(item);
    verify(userRepository).searchPickerItems("alice@", pageable);
  }

  @Test
  void resolvePickerItems_preservesRequestedOrderAndDropsUnknownIds() {
    UserPickerItem first = new UserPickerItem("u1", "Alice", "alice@example.com", null, null);
    UserPickerItem second = new UserPickerItem("u2", "Bob", "bob@example.com", null, null);
    when(userRepository.findPickerItemsByIds(any())).thenReturn(List.of(first, second));

    Collection<UserPickerItem> result = service.resolvePickerItems(List.of(" u2 ", "u1", "missing", "u2"));

    assertThat(result).containsExactly(second, first);
  }

  @Test
  void resolvePickerItems_rejectsUnboundedHydration() {
    List<String> ids = java.util.stream.IntStream.range(0, 101)
        .mapToObj(index -> "user-" + index)
        .toList();

    assertThatThrownBy(() -> service.resolvePickerItems(ids))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    verify(userRepository, never()).findPickerItemsByIds(any());
  }

  // ── authorize ─────────────────────────────────────────────────────────────

  @Test
  void authorize_emptyRoleIds_noValidation() {
    UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
    dto.setUserId("u1");
    dto.setRoleIds(Set.of());

    Collection<UserRoleRelevance> result = service.authorize(dto);

    assertThat(result).isEmpty();
    verify(roleRepository, never()).findAllByIds(any());
    verify(userRoleRelevanceRepository, never()).saveAll(any());
  }

  @Test
  void authorize_noContext_savesWithNullTenantId() {
    UserRoleRelevance rel = new UserRoleRelevance();
    Role role = new Role();
    role.setId("r1");
    role.setTenantId(null);
    when(userRoleRelevanceRepository.saveAll(any())).thenReturn(List.of(rel));
    when(roleRepository.findAllByIds(Set.of("r1"))).thenReturn(List.of(role));

    UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
    dto.setUserId("u1");
    dto.setRoleIds(Set.of("r1"));

    Collection<UserRoleRelevance> result = service.authorize(dto);

    assertThat(result).containsExactly(rel);
    // No tenant context and no personal tenant found → tenantId is null
    verify(userRoleRelevanceRepository).saveAll(argThat(items -> {
      UserRoleRelevance item = ((java.util.Collection<UserRoleRelevance>) items).iterator().next();
      return item.getTenantId() == null;
    }));
  }

  @Test
  void authorize_withValidRolesAsAdmin_savesAndRefreshesVersion() {
    AuthorizationContext ctx = buildCtx("admin", true, "tenant1");
    Role role = new Role();
    role.setId("r1");
    role.setTenantId("tenant1");

    try (MockedStatic<AuthorizationContextHolder> ctxMock = mockStatic(AuthorizationContextHolder.class)) {
      ctxMock.when(AuthorizationContextHolder::getContext).thenReturn(ctx);

      UserRoleRelevance rel = new UserRoleRelevance();
      when(userRoleRelevanceRepository.saveAll(any())).thenReturn(List.of(rel));
      when(roleRepository.findAllByIds(Set.of("r1"))).thenReturn(List.of(role));

      UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
      dto.setUserId("u1");
      dto.setRoleIds(Set.of("r1"));

      Collection<UserRoleRelevance> result = service.authorize(dto);

      assertThat(result).containsExactly(rel);
      // saveAll is called with tenant1 scope
      verify(userRoleRelevanceRepository).saveAll(argThat(items -> {
        UserRoleRelevance item = ((java.util.Collection<UserRoleRelevance>) items).iterator().next();
        return "tenant1".equals(item.getTenantId());
      }));
      // Permission version is refreshed for the active tenant context
      verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
    }
  }

  // ── unauthorized ──────────────────────────────────────────────────────────

  @Test
  void unauthorized_noContext_usesNullTenantScope() {
    Role role = new Role();
    role.setId("r1");
    role.setTenantId(null);
    when(roleRepository.findAllByIds(Set.of("r1", "r2"))).thenReturn(List.of(role, roleWithId("r2", null)));

    UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
    dto.setUserId("u1");
    Set<String> roleIds = Set.of("r1", "r2");
    dto.setRoleIds(roleIds);

    service.unauthorized(dto);

    verify(userRoleRelevanceRepository).unauthorized(null, "u1", roleIds);
  }

  @Test
  void unauthorized_withTenantContext_usesTenantScopeAndRefreshesVersion() {
    AuthorizationContext ctx = buildCtx("owner1", false, "tenant1");
    Role role = new Role();
    role.setId("r1");
    role.setTenantId("tenant1");
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");

    try (MockedStatic<AuthorizationContextHolder> ctxMock = mockStatic(AuthorizationContextHolder.class)) {
      ctxMock.when(AuthorizationContextHolder::getContext).thenReturn(ctx);
      when(roleRepository.findAllByIds(Set.of("r1"))).thenReturn(List.of(role));
      when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));

      UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
      dto.setUserId("u1");
      dto.setRoleIds(Set.of("r1"));

      service.unauthorized(dto);

      // Uses the current tenant scope
      verify(userRoleRelevanceRepository).unauthorized("tenant1", "u1", Set.of("r1"));
      // Refreshes authorization version for the active tenant
      verify(resourceAuthorizationVersionService).refreshTenant("tenant1");
    }
  }

  @Test
  void unauthorized_withNonDefaultTenantNonOwner_isDenied() {
    AuthorizationContext ctx = buildCtx("regular", false, "tenant1");
    Tenant tenant = new Tenant();
    tenant.setId("tenant1");
    tenant.setOwnerId("owner1");

    try (MockedStatic<AuthorizationContextHolder> ctxMock = mockStatic(AuthorizationContextHolder.class)) {
      ctxMock.when(AuthorizationContextHolder::getContext).thenReturn(ctx);
      when(tenantRepository.findById("tenant1")).thenReturn(Optional.of(tenant));

      UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
      dto.setUserId("u1");
      dto.setRoleIds(Set.of("r1"));

      assertThatThrownBy(() -> service.unauthorized(dto))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessage("仅租户所有者或租户管理员可以为成员分配当前租户角色");
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static AuthorizationContext buildCtx(String userId, boolean isAdmin, String tenantId) {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId(userId);
    ctx.setIsAdministrator(isAdmin);
    if (tenantId != null) {
      ctx.setAttributes(Map.of("X-Tenant-Id", tenantId));
    }
    return ctx;
  }

  private static Role roleWithId(String roleId, String tenantId) {
    Role role = new Role();
    role.setId(roleId);
    role.setTenantId(tenantId);
    return role;
  }
}
