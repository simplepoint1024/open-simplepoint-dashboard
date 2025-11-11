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
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserRoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.UserRoleRelevance;
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

  private final UserRoleRelevanceRepository userRoleRelevanceRepository;

  /**
   * Constructs a new RolesServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                  the RoleRepository instance for data access
   * @param userContext                 the user context for retrieving current user information
   * @param detailsProviderService      the service for providing user details
   * @param userRoleRelevanceRepository the repository for managing UserRoleRelevance entities
   */
  public RolesServiceImpl(RoleRepository repository,
                          @Autowired(required = false) UserContext<BaseUser> userContext,
                          DetailsProviderService detailsProviderService,
                          UserRoleRelevanceRepository userRoleRelevanceRepository) {
    super(repository, userContext, detailsProviderService);
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
  }

  @Override
  public UserRoleRelevanceRepository getUserRoleRelevanceRepository() {
    return this.userRoleRelevanceRepository;
  }

  @Override
  public Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(Collection<String> roleAuthorities) {
    return getRepository().loadPermissionsByRoleAuthorities(roleAuthorities);
  }

  /**
   * Retrieves a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  @Override
  public Page<UserRoleRelevanceVo> roleSelectItems(Pageable pageable) {
    return getRepository().roleSelectItems(pageable);
  }


  /**
   * Retrieves a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A collection of role authorities for the given username.
   */
  @Override
  public Collection<String> authorized(String username) {
    return getRepository().authorized(username);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto) {
    Set<String> roleAuthorities = dto.getRoleAuthorities();
    Set<UserRoleRelevance> authorities = new HashSet<>(roleAuthorities.size());
    for (String roleAuthority : roleAuthorities) {
      UserRoleRelevance relevance = new UserRoleRelevance();
      relevance.setUsername(dto.getUsername());
      relevance.setAuthority(roleAuthority);
      authorities.add(relevance);
    }
    return userRoleRelevanceRepository.saveAll(authorities);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(UserRoleRelevanceDto dto) {
    userRoleRelevanceRepository.unauthorized(dto.getUsername(), dto.getRoleAuthorities());
  }
}
