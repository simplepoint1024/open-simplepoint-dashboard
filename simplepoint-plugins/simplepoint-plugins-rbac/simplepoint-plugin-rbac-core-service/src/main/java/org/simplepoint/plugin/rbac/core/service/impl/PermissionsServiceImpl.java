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
import java.util.Set;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RolePermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PermissionsServiceImpl provides the implementation for PermissionsService.
 * It utilizes the repository to load permissions associated with a specific role authority.
 */
@Service
public class PermissionsServiceImpl
    extends BaseServiceImpl<PermissionsRepository, Permissions, String>
    implements PermissionsService {

  private final RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;

  /**
   * Constructs a PermissionsServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                         the PermissionsRepository for data access
   * @param userContext                        the UserContext for accessing user-related information
   * @param detailsProviderService             the DetailsProviderService for additional details
   * @param rolePermissionsRelevanceRepository the RolePermissionsRelevanceRepository for role-permission relations
   */
  public PermissionsServiceImpl(
      PermissionsRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService,
      RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository
  ) {
    super(repository, userContext, detailsProviderService);
    this.rolePermissionsRelevanceRepository = rolePermissionsRelevanceRepository;
  }

  /**
   * Removes the authorization of specified permissions from a role.
   *
   * @param roleAuthority the authority of the role
   * @param authorities   the set of permission authorities to be unauthorized
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(String roleAuthority, Set<String> authorities) {
    this.getRepository().unauthorized(roleAuthority, authorities);
  }

  /**
   * Retrieves a paginated list of permission items along with their associated roles.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo containing permission items and their roles
   */
  @Override
  public Page<RolePermissionsRelevanceVo> permissionItems(Pageable pageable) {
    return getRepository().permissionItems(pageable);
  }

  /**
   * Retrieves a collection of authorized permission authorities for a given role authority.
   *
   * @param roleAuthority the authority of the role
   * @return a collection of authorized permission authorities
   */
  @Override
  public Collection<String> authorized(String roleAuthority) {
    return getRepository().authorized(roleAuthority);
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
    Set<String> permissionAuthorities = dto.getPermissionAuthorities();
    Set<RolePermissionsRelevance> permissionRelevantAuthorities = new HashSet<>();
    for (String permissionAuthority : permissionAuthorities) {
      RolePermissionsRelevance relevance = new RolePermissionsRelevance();
      relevance.setRoleAuthority(dto.getRoleAuthority());
      relevance.setPermissionAuthority(permissionAuthority);
      permissionRelevantAuthorities.add(relevance);
    }
    return this.rolePermissionsRelevanceRepository.saveAll(permissionRelevantAuthorities);
  }
}
