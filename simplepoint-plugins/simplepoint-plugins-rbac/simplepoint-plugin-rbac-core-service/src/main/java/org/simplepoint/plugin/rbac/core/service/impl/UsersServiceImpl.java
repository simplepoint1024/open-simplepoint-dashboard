/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ResourceGrantLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.command.ChangePasswordCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation class for managing User entities
 * in the RBAC (Role-Based Access Control) system.
 * This class extends BaseServiceImpl to inherit common CRUD operations and implements both
 * UsersService and UserDetailsService interfaces to handle user-specific operations and
 * authentication-related logic.
 */
@Slf4j
@Service
@RemoteProvider
public class UsersServiceImpl extends BaseServiceImpl<UserRepository, User, String>
    implements UsersService {

  private final UserRoleRelevanceRepository userRoleRelevanceRepository;
  private final TenantRepository tenantRepository;
  private final TenantUserRelevanceRepository tenantUserRelevanceRepository;
  private final RoleRepository roleRepository;
  private final ResourceAuthorizationVersionService resourceAuthorizationVersionService;
  private final ResourceGrantLogRemoteService resourceGrantLogRemoteService;
  private final ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider;

  /**
     * Optional password encoder for encrypting user passwords.
     * If no encoder is configured, passwords will not be encrypted.
     */
  private final PasswordEncoder passwordEncoder;

  /**
     * Constructs a UsersServiceImpl with the specified repository and optional metadata sync service.
     *
     * @param passwordEncoder             the optional PasswordEncoder for encrypting user passwords
     * @param usersRepository             the repository used for user operations
     * @param detailsProviderService      the access control service for managing resource grants
     * @param userRoleRelevanceRepository the repository for managing UserRoleRelevance entities
     */
  public UsersServiceImpl(
      final PasswordEncoder passwordEncoder,
      final UserRepository usersRepository,
      final DetailsProviderService detailsProviderService,
      final UserRoleRelevanceRepository userRoleRelevanceRepository,
      @Autowired(required = false) final TenantRepository tenantRepository,
      @Autowired(required = false) final TenantUserRelevanceRepository tenantUserRelevanceRepository,
      final RoleRepository roleRepository,
      final ResourceAuthorizationVersionService resourceAuthorizationVersionService,
      final ResourceGrantLogRemoteService resourceGrantLogRemoteService,
      final ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider
  ) {
    super(usersRepository, detailsProviderService);
    this.passwordEncoder = passwordEncoder;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.tenantUserRelevanceRepository = tenantUserRelevanceRepository;
    this.roleRepository = roleRepository;
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
    this.resourceGrantLogRemoteService = resourceGrantLogRemoteService;
    this.builtInTenantProvisionerProvider = builtInTenantProvisionerProvider;
  }

  @Override
  public <S extends User> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return super.limit(attributes, pageable);
    }
    String tenantId = resolveCurrentTenantScope();
    Set<String> userIds = currentTenantUserIds(tenantId);
    if (userIds.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("id", "in:" + String.join(",", userIds));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<User> findById(String id) {
    Optional<User> user = super.findById(id);
    if (user.isEmpty() || AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return user;
    }
    String tenantId = resolveCurrentTenantScope();
    return currentTenantUserIds(tenantId).contains(id) ? user : Optional.empty();
  }

  @Override
  public Optional<User> findByIdForAuthorization(String userId) {
    return getRepository().findByIdForAuthorization(userId);
  }


  /**
     * Loads a user by their username for authentication purposes.
     * This method retrieves the user from the repository and sets their roles as authorities.
     *
     * @param subject the email/phone of the user to load
     * @return the User object with populated authorities
     * @throws UsernameNotFoundException if no user or multiple
     *                                   users are found with the given username
     */
  @Override
  public UserDetails loadUserByUsername(String subject) throws UsernameNotFoundException {
    var user = loadUserByPhoneOrEmail(subject);
    if (user == null) {
      log.warn("User not found: {}", subject);
      throw new UsernameNotFoundException("User not found: " + subject);
    }
    ensurePersonalTenantExists(user);
    return user;
  }

  /**
     * Loads roles associated with the given username.
     *
     * @param tenantId the tenant ID to which the user belongs
     * @param userId   the username of the user
     * @return a list of role authorities assigned to the user
     */
  @Override
  public Collection<RoleGrantedAuthority> loadRolesByUserId(String tenantId, String userId) {
    return getRepository().loadRolesByUserId(tenantId, userId);
  }

  /**
   * Loads resources associated with the given roles.
   *
   * @param roleIds a list of role ids
   * @return resource codes granted to the specified roles
   */
  @Override
  public Collection<String> loadResourcesInRoleIds(List<String> roleIds) {
    return getRepository().loadResourcesInRoleIds(roleIds);
  }

  /**
     * Adds a new user to the system.
     * This method validates the uniqueness of the username and
     * optionally encrypts the user's password
     * if a PasswordEncoder is configured.
     *
     * @param entity the User object to add
     * @param <S>    the type of the User entity
     * @return the added User object
     */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends User> S create(S entity) {
    entity.setPassword(passwordEncoder.encode(entity.getPassword()));
    S created = super.create(entity);
    ensurePersonalTenantExists(created);
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      ensureTenantMembership(resolveCurrentTenantScope(), created.getId());
      refreshCurrentTenantAuthorizationVersion();
    }
    return created;
  }

  private void ensurePersonalTenantExists(User user) {
    if (tenantRepository == null || tenantUserRelevanceRepository == null) {
      return;
    }
    if (user == null || user.getId() == null || user.getId().isBlank()) {
      return;
    }
    var existing = Optional.ofNullable(tenantRepository.findPersonalTenantByOwnerId(user.getId()))
        .orElse(Optional.empty());
    if (existing.isPresent()) {
      ensureTenantMembership(existing.get().getId(), user.getId());
      provisionPersonalTenant(existing.get().getId());
      return;
    }
    String displayName = (user.getName() != null && !user.getName().isBlank())
        ? user.getName()
        : user.getId();
    Tenant tenant = new Tenant();
    tenant.setName(displayName + " 的个人空间");
    tenant.setDescription("个人租户");
    tenant.setTenantType(TenantType.PERSONAL);
    tenant.setOwnerId(user.getId());
    tenant.setAuthorizationVersion(0L);
    Tenant saved = tenantRepository.save(tenant);
    ensureTenantMembership(saved.getId(), user.getId());
    provisionPersonalTenant(saved.getId());
  }

  private void provisionPersonalTenant(String tenantId) {
    if (builtInTenantProvisionerProvider == null) {
      return;
    }
    BuiltInTenantProvisioner provisioner = builtInTenantProvisionerProvider.getIfAvailable();
    if (provisioner != null) {
      provisioner.provisionPersonalTenant(tenantId);
    }
  }

  private void ensureTenantMembership(String tenantId, String userId) {
    if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
      return;
    }
    Collection<String> authorizedUserIds = tenantUserRelevanceRepository.authorized(tenantId);
    if (authorizedUserIds != null && authorizedUserIds.contains(userId)) {
      return;
    }
    TenantUserRelevance rel = new TenantUserRelevance();
    rel.setTenantId(tenantId);
    rel.setUserId(userId);
    tenantUserRelevanceRepository.saveAll(List.of(rel));
  }

  @Override
  public <S extends User> User modifyById(S entity) {
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      validateUserBelongsToCurrentTenant(entity.getId());
    }
    var password = entity.getPassword();
    if (password != null && !password.isEmpty()) {
      entity.setPassword(password.matches("\\A\\$2([ayb])?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}")
          ? password
          : passwordEncoder.encode(password)
      );

    }
    return super.modifyById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      validateUsersBelongToCurrentTenant(ids);
    }
    super.removeByIds(ids);
    if (tenantRepository == null || ids == null || ids.isEmpty()) {
      return;
    }
    // Clean up personal tenants owned by deleted users
    List<String> personalTenantIds = ids.stream()
        .filter(Objects::nonNull)
        .map(userId -> tenantRepository.findPersonalTenantByOwnerId(userId)
            .map(Tenant::getId)
            .orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    if (!personalTenantIds.isEmpty()) {
      if (tenantUserRelevanceRepository != null) {
        tenantUserRelevanceRepository.deleteAllByTenantIds(personalTenantIds);
      }
      tenantRepository.deleteByIds(personalTenantIds);
    }
  }


  @Override
  public Collection<String> authorized(String userId) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    return getRepository().authorized(resolveCurrentTenantScope(), userId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    Set<String> roleIds = dto.getRoleIds() == null ? Set.of() : dto.getRoleIds();
    if (roleIds.isEmpty()) {
      return List.of();
    }
    validateRolesBelongToCurrentTenant(roleIds);
    String tenantScope = resolveCurrentTenantScope();
    Set<UserRoleRelevance> authorities = new HashSet<>(roleIds.size());
    for (String roleId : roleIds) {
      UserRoleRelevance relevance = new UserRoleRelevance();
      relevance.setUserId(dto.getUserId());
      relevance.setRoleId(roleId);
      relevance.setTenantId(tenantScope);
      authorities.add(relevance);
    }
    Collection<UserRoleRelevance> saved = userRoleRelevanceRepository.saveAll(authorities);
    refreshCurrentTenantAuthorizationVersion();
    recordResourceGrantChange("AUTHORIZE", dto);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(UserRoleRelevanceDto dto) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    Set<String> roleIds = dto.getRoleIds() == null ? Set.of() : dto.getRoleIds();
    if (roleIds.isEmpty()) {
      return;
    }
    validateRolesBelongToCurrentTenant(roleIds);
    userRoleRelevanceRepository.unauthorized(resolveCurrentTenantScope(), dto.getUserId(), roleIds);
    refreshCurrentTenantAuthorizationVersion();
    recordResourceGrantChange("UNAUTHORIZE", dto);
  }

  @Override
  public Page<RoleRelevanceVo> roleCandidates(Pageable pageable) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    return roleRepository.roleSelectItems(resolveCurrentTenantScope(), pageable);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void changePassword(String userId, ChangePasswordCommand command) {
    if (command.getCurrentPassword() == null || command.getCurrentPassword().isBlank()) {
      throw new IllegalArgumentException("当前密码不能为空");
    }
    if (command.getNewPassword() == null || command.getNewPassword().isBlank()) {
      throw new IllegalArgumentException("新密码不能为空");
    }
    if (command.getNewPassword().length() < 6) {
      throw new IllegalArgumentException("新密码长度不能少于 6 位");
    }
    if (!Objects.equals(command.getNewPassword(), command.getConfirmPassword())) {
      throw new IllegalArgumentException("新密码与确认密码不一致");
    }
    User user = findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    if (!passwordEncoder.matches(command.getCurrentPassword(), user.getPassword())) {
      throw new AccessDeniedException("当前密码不正确");
    }
    user.setPassword(passwordEncoder.encode(command.getNewPassword()));
    super.modifyById(user);
  }

  @Override
  public User loadUserByPhoneOrEmail(String phoneOrEmail) {
    return userRoleRelevanceRepository.loadUserByPhoneOrEmail(phoneOrEmail);
  }

  private void validateRolesBelongToCurrentTenant(Set<String> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      return;
    }
    String tenantId = resolveCurrentTenantScope();
    Set<String> currentTenantRoleIds = roleRepository.findAllByIds(roleIds).stream()
        .filter(Objects::nonNull)
        .filter(role -> Objects.equals(role.getTenantId(), tenantId))
        .map(Role::getId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (currentTenantRoleIds.size() == roleIds.size()) {
      return;
    }
    LinkedHashSet<String> invalidRoleIds = new LinkedHashSet<>(roleIds);
    invalidRoleIds.removeAll(currentTenantRoleIds);
    throw new IllegalArgumentException("角色不存在或不属于当前租户: " + String.join(",", invalidRoleIds));
  }

  private void validateUserBelongsToCurrentTenant(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("用户ID不能为空");
    }
    String tenantId = resolveCurrentTenantScope();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    if (!currentTenantUserIds(tenantId).contains(userId)) {
      throw new IllegalArgumentException("用户不存在或不属于当前租户");
    }
  }

  private void validateUsersBelongToCurrentTenant(Collection<String> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    String tenantId = resolveCurrentTenantScope();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    Set<String> currentTenantUserIds = currentTenantUserIds(tenantId);
    LinkedHashSet<String> invalidUserIds = userIds.stream()
        .filter(userId -> userId != null && !userId.isBlank())
        .filter(userId -> !currentTenantUserIds.contains(userId))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!invalidUserIds.isEmpty()) {
      throw new IllegalArgumentException("用户不存在或不属于当前租户: " + String.join(",", invalidUserIds));
    }
  }

  private Set<String> currentTenantUserIds(String tenantId) {
    if (tenantId == null || tenantId.isBlank() || tenantUserRelevanceRepository == null) {
      return Set.of();
    }
    return tenantUserRelevanceRepository.authorized(tenantId).stream()
        .filter(userId -> userId != null && !userId.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private void requireTenantOwnerOrAdministratorIfTenantScoped() {
    String tenantId = resolveCurrentTenantScope();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    AuthorizationContext ctx = getAuthorizationContext();
    if (Boolean.TRUE.equals(ctx.getIsAdministrator())) {
      return;
    }
    var tenant = tenantRepository.findById(tenantId)
        .orElseThrow(() -> new IllegalArgumentException("租户不存在"));
    if (tenant.getTenantType() == org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL) {
      throw new AccessDeniedException("个人空间不支持租户角色分配");
    }
    if (!AuthorizationScopeGuards.isTenantManager(ctx, tenant.getOwnerId())) {
      throw new AccessDeniedException("仅租户所有者或租户管理员可以为成员分配当前租户角色");
    }
  }

  private String resolveCurrentTenantScope() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    if (tenantRepository == null) {
      return null;
    }
    var ctx = getAuthorizationContext();
    String userId = ctx != null ? ctx.getUserId() : null;
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return tenantRepository.findPersonalTenantByOwnerId(userId)
        .map(Tenant::getId)
        .orElse(null);
  }

  private void refreshCurrentTenantAuthorizationVersion() {
    String tenantId = resolveCurrentTenantScope();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    resourceAuthorizationVersionService.refreshTenant(tenantId);
  }

  private void recordResourceGrantChange(String action, UserRoleRelevanceDto dto) {
    AuthorizationContext authorizationContext = getAuthorizationContext();
    if (authorizationContext == null || authorizationContext.getUserId() == null || authorizationContext.getUserId().isBlank()) {
      return;
    }

    Set<String> roleIds = dto.getRoleIds() == null ? Set.of() : dto.getRoleIds();
    ResourceGrantLogRecordCommand command = new ResourceGrantLogRecordCommand();
    command.setChangedAt(Instant.now());
    command.setChangeType("USER_ROLE");
    command.setAction(action);
    command.setSubjectType("USER");
    command.setSubjectId(dto.getUserId());
    command.setSubjectLabel(resolveUserLabel(dto.getUserId()));
    command.setTargetType("ROLE");
    command.setTargetSummary(resolveRoleSummary(roleIds));
    command.setTargetCount(roleIds.size());
    command.setOperatorId(authorizationContext.getUserId());
    command.setTenantId(resolveCurrentTenantScope());
    command.setContextId(authorizationContext.getContextId());
    command.setSourceService("common");
    command.setDescription(action + " USER_ROLE [" + command.getSubjectLabel() + "] -> [" + command.getTargetSummary() + "]");
    resourceGrantLogRemoteService.record(command);
  }

  private String resolveUserLabel(String userId) {
    return findById(userId)
        .map(user -> firstNonBlank(user.getName(), user.getNickname(), user.getEmail(), user.getPhoneNumber(), user.getId()))
        .orElse(userId);
  }

  private String resolveRoleSummary(Set<String> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      return "";
    }
    LinkedHashSet<String> labels = roleRepository.findAllByIds(roleIds).stream()
        .filter(Objects::nonNull)
        .map(role -> firstNonBlank(role.getAuthority(), role.getRoleName(), role.getId()))
        .filter(label -> label != null && !label.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (labels.isEmpty()) {
      labels.addAll(roleIds);
    }
    return String.join(",", labels);
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

}
