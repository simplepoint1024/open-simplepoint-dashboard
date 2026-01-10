/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.authority.PermissionGrantedAuthority;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation class for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This class extends the BaseServiceImpl to inherit common CRUD operations and implements the RolesService
 * interface for defining role-specific business logic.
 */
@Service
public class RolesServiceImpl extends BaseServiceImpl<RoleRepository, Role, String>
    implements RoleService {

  private final PermissionsService permissionsService;

  private final RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  private final AuthorizationContextCacheable authorizationContextCacheable;

  /**
   * Constructs a new RolesServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                         the RoleRepository instance for data access
   * @param userContext                        the user context for retrieving current user information
   * @param detailsProviderService             the service for providing user details
   * @param rolePermissionsRelevanceRepository the RolePermissionsRelevanceRepository for role-permission relations
   * @param authorizationContextCacheable      the AuthorizationContextCacheable for caching authorization contexts
   */
  public RolesServiceImpl(
      RoleRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService, PermissionsService permissionsService,
      RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository,
      AuthorizationContextCacheable authorizationContextCacheable
  ) {
    super(repository, userContext, detailsProviderService);
    this.permissionsService = permissionsService;
    this.rolePermissionsRelevanceRepository = rolePermissionsRelevanceRepository;

    this.authorizationContextCacheable = authorizationContextCacheable;
  }

  /**
   * Retrieves a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  @Override
  public Page<RoleRelevanceVo> roleSelectItems(Pageable pageable) {
    return getRepository().roleSelectItems(pageable);
  }


  /**
   * Removes the authorization of specified permissions from a role.
   *
   * @param roleId        the authority of the role
   * @param permissionIds the set of permission permissionIds to be unauthorized
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(String roleId, Set<String> permissionIds) {
    this.getRepository().unauthorized(roleId, permissionIds);
  }

  /**
   * Retrieves a collection of authorized permission permissionIds for a given role authority.
   *
   * @param roleId the authority of the role
   * @return a collection of authorized permission permissionIds
   */
  @Override
  public Collection<String> authorized(String roleId) {
    return getRepository().authorized(roleId);
  }

  /**
   * Authorizes a set of permissions to a role based on the provided DTO.
   *
   * @param dto the RolePermissionsRelevanceDto containing role authority and permission authorities
   * @return a collection of RolePermissionsRelevance representing the authorized permissions
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<RolePermissionsRelevance> authorize(RolePermissionsRelevanceDto dto) {
    Set<String> permissionIds = dto.getPermissionIds();
    Set<RolePermissionsRelevance> rels = new HashSet<>();
    String roleId = dto.getRoleId();
    for (String permissionId : permissionIds) {
      RolePermissionsRelevance relevance = new RolePermissionsRelevance();
      relevance.setRoleId(roleId);
      relevance.setPermissionId(permissionId);
      rels.add(relevance);
    }
    List<RolePermissionsRelevance> saved = this.rolePermissionsRelevanceRepository.saveAll(rels);

    Role role = findById(roleId).get();

    List<Permissions> permissions = permissionsService.findAllByIds(permissionIds);
    List<PermissionGrantedAuthority> permissionGrantedAuthorities =
        permissions.stream().map(pm -> new PermissionGrantedAuthority(pm.getId(), pm.getAuthority(), role.getId(), role.getAuthority())).toList();
    // 缓存角色对应的权限
    authorizationContextCacheable.cachePermission(
        dto.getRoleId(),
        permissionGrantedAuthorities
    );
    return saved;
  }
}
