package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.AccessCenterRoleAuthorizationDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleOverviewVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterScopeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterUserImpactVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.AccessCenterService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default access-center aggregation service.
 */
@Service
public class AccessCenterServiceImpl implements AccessCenterService {

  private final RoleService roleService;
  private final RoleRepository roleRepository;
  private final UserRoleRelevanceRepository userRoleRelevanceRepository;
  private final DataScopeRepository dataScopeRepository;
  private final FieldScopeRepository fieldScopeRepository;

  public AccessCenterServiceImpl(
      RoleService roleService,
      RoleRepository roleRepository,
      UserRoleRelevanceRepository userRoleRelevanceRepository,
      DataScopeRepository dataScopeRepository,
      FieldScopeRepository fieldScopeRepository
  ) {
    this.roleService = roleService;
    this.roleRepository = roleRepository;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.dataScopeRepository = dataScopeRepository;
    this.fieldScopeRepository = fieldScopeRepository;
  }

  @Override
  public Page<AccessCenterRoleOverviewVo> roleOverviews(Pageable pageable) {
    Page<RoleRelevanceVo> roles = roleService.roleSelectItems(pageable);
    Set<String> roleIds = roles.getContent().stream()
        .map(RoleRelevanceVo::getId)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, Role> roleMap = roleRepository.findAllByIds(roleIds).stream()
        .collect(Collectors.toMap(Role::getId, Function.identity(), (left, right) -> left));
    List<RoleScopeAssignmentVo> assignments = roles.getContent().stream()
        .map(RoleRelevanceVo::getId)
        .filter(this::hasText)
        .map(roleService::getScopeAssignment)
        .toList();
    Map<String, RoleScopeAssignmentVo> assignmentMap = assignments.stream()
        .collect(Collectors.toMap(RoleScopeAssignmentVo::getRoleId, Function.identity(), (left, right) -> left));
    Map<String, AccessCenterScopeVo> dataScopes = loadDataScopes(assignments);
    Map<String, AccessCenterScopeVo> fieldScopes = loadFieldScopes(assignments);
    List<AccessCenterRoleOverviewVo> content = roles.getContent().stream()
        .map(role -> toOverview(role, roleMap.get(role.getId()), assignmentMap.get(role.getId()), dataScopes, fieldScopes))
        .toList();
    return new PageImpl<>(content, pageable, roles.getTotalElements());
  }

  @Override
  public AccessCenterRoleDetailVo roleDetail(String roleId) {
    if (!hasText(roleId)) {
      throw new IllegalArgumentException("roleId is required");
    }
    Collection<String> authorized = roleService.authorized(roleId);
    RoleScopeAssignmentVo assignment = roleService.getScopeAssignment(roleId);
    if (assignment == null) {
      assignment = new RoleScopeAssignmentVo();
      assignment.setRoleId(roleId);
    }
    Role role = roleRepository.findById(roleId)
        .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    AccessCenterRoleDetailVo detail = new AccessCenterRoleDetailVo();
    detail.setRole(toRoleVo(role));
    detail.setAuthorizedPermissions(normalizeAuthorities(authorized));
    detail.setScopeAssignment(assignment);
    detail.setDataScope(loadDataScope(assignment.getDataScopeId()));
    detail.setFieldScope(loadFieldScope(assignment.getFieldScopeId()));
    detail.setAssignedUserCount(countAssignedUsers(role));
    detail.setAssignedUsers(loadAssignedUsers(role));
    return detail;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AccessCenterRoleDetailVo saveRoleAuthorization(AccessCenterRoleAuthorizationDto dto) {
    if (dto == null || !hasText(dto.getRoleId())) {
      throw new IllegalArgumentException("roleId is required");
    }
    String roleId = dto.getRoleId();
    Set<String> current = normalizeAuthorities(roleService.authorized(roleId));
    Set<String> next = normalizeAuthorities(dto.getPermissionAuthorities());

    Set<String> toAdd = new LinkedHashSet<>(next);
    toAdd.removeAll(current);
    Set<String> toRemove = new LinkedHashSet<>(current);
    toRemove.removeAll(next);

    if (!toAdd.isEmpty()) {
      RolePermissionsRelevanceDto authorizeDto = new RolePermissionsRelevanceDto();
      authorizeDto.setRoleId(roleId);
      authorizeDto.setPermissionAuthority(toAdd);
      authorizeDto.setDataScopeId(emptyToNull(dto.getDataScopeId()));
      authorizeDto.setFieldScopeId(emptyToNull(dto.getFieldScopeId()));
      roleService.authorize(authorizeDto);
    }
    if (!toRemove.isEmpty()) {
      roleService.unauthorized(roleId, toRemove);
    }

    RoleScopeAssignmentVo scope = new RoleScopeAssignmentVo();
    scope.setRoleId(roleId);
    scope.setDataScopeId(emptyToNull(dto.getDataScopeId()));
    scope.setFieldScopeId(emptyToNull(dto.getFieldScopeId()));
    roleService.updateScopeAssignment(scope);
    return roleDetail(roleId);
  }

  private AccessCenterRoleOverviewVo toOverview(
      RoleRelevanceVo role,
      Role entity,
      RoleScopeAssignmentVo assignment,
      Map<String, AccessCenterScopeVo> dataScopes,
      Map<String, AccessCenterScopeVo> fieldScopes
  ) {
    RoleScopeAssignmentVo resolvedAssignment = assignment == null ? new RoleScopeAssignmentVo() : assignment;
    return new AccessCenterRoleOverviewVo(
        role,
        roleService.authorized(role.getId()).size(),
        countAssignedUsers(entity),
        dataScopes.get(resolvedAssignment.getDataScopeId()),
        fieldScopes.get(resolvedAssignment.getFieldScopeId())
    );
  }

  private RoleRelevanceVo toRoleVo(Role role) {
    return new RoleRelevanceVo(role.getId(), role.getRoleName(), role.getAuthority(), role.getDescription());
  }

  private long countAssignedUsers(Role role) {
    if (role == null || !hasText(role.getTenantId()) || !hasText(role.getId())) {
      return 0L;
    }
    return userRoleRelevanceRepository.countByTenantIdAndRoleId(role.getTenantId(), role.getId());
  }

  private List<AccessCenterUserImpactVo> loadAssignedUsers(Role role) {
    if (role == null || !hasText(role.getTenantId()) || !hasText(role.getId())) {
      return List.of();
    }
    return userRoleRelevanceRepository.findUsersByTenantIdAndRoleId(role.getTenantId(), role.getId());
  }

  private Map<String, AccessCenterScopeVo> loadDataScopes(Collection<RoleScopeAssignmentVo> assignments) {
    Set<String> ids = assignments.stream()
        .map(RoleScopeAssignmentVo::getDataScopeId)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return dataScopeRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(DataScope::getId, this::toScopeVo, (left, right) -> left));
  }

  private Map<String, AccessCenterScopeVo> loadFieldScopes(Collection<RoleScopeAssignmentVo> assignments) {
    Set<String> ids = assignments.stream()
        .map(RoleScopeAssignmentVo::getFieldScopeId)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return fieldScopeRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(FieldScope::getId, this::toScopeVo, (left, right) -> left));
  }

  private AccessCenterScopeVo loadDataScope(String id) {
    if (!hasText(id)) {
      return null;
    }
    return dataScopeRepository.findById(id).map(this::toScopeVo).orElse(null);
  }

  private AccessCenterScopeVo loadFieldScope(String id) {
    if (!hasText(id)) {
      return null;
    }
    return fieldScopeRepository.findById(id).map(this::toScopeVo).orElse(null);
  }

  private AccessCenterScopeVo toScopeVo(DataScope scope) {
    return new AccessCenterScopeVo(
        scope.getId(),
        scope.getName(),
        scope.getType() == null ? null : scope.getType().name(),
        scope.getDescription()
    );
  }

  private AccessCenterScopeVo toScopeVo(FieldScope scope) {
    return new AccessCenterScopeVo(scope.getId(), scope.getName(), null, scope.getDescription());
  }

  private Set<String> normalizeAuthorities(Collection<String> authorities) {
    if (authorities == null || authorities.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return authorities.stream()
        .filter(this::hasText)
        .map(String::trim)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String emptyToNull(String value) {
    return hasText(value) ? value.trim() : null;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
