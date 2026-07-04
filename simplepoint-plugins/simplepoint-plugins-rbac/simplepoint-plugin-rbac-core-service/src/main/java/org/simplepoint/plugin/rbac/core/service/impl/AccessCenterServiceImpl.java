package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.AccessCenterRoleAuthorizationDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterResourceNodeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleOverviewVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterScopeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterUserImpactVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.AccessCenterService;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.Menu;
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

  private static final String NODE_TYPE_GROUP = "GROUP";
  private static final String NODE_TYPE_MENU = "MENU";
  private static final String NODE_TYPE_FEATURE = "FEATURE";
  private static final String NODE_TYPE_PERMISSION = "PERMISSION";

  private final RoleService roleService;
  private final PermissionsService permissionsService;
  private final RoleRepository roleRepository;
  private final UserRoleRelevanceRepository userRoleRelevanceRepository;
  private final DataScopeRepository dataScopeRepository;
  private final FieldScopeRepository fieldScopeRepository;
  private final MenuRepository menuRepository;
  private final MenuFeatureRelevanceRepository menuFeatureRelevanceRepository;
  private final FeatureService featureService;

  /**
   * Creates the access-center aggregation service.
   *
   * @param roleService role service
   * @param permissionsService permissions service
   * @param roleRepository role repository
   * @param userRoleRelevanceRepository user-role relation repository
   * @param dataScopeRepository data scope repository
   * @param fieldScopeRepository field scope repository
   * @param menuRepository menu repository
   * @param menuFeatureRelevanceRepository menu-feature relation repository
   * @param featureService feature service
   */
  public AccessCenterServiceImpl(
      RoleService roleService,
      PermissionsService permissionsService,
      RoleRepository roleRepository,
      UserRoleRelevanceRepository userRoleRelevanceRepository,
      DataScopeRepository dataScopeRepository,
      FieldScopeRepository fieldScopeRepository,
      MenuRepository menuRepository,
      MenuFeatureRelevanceRepository menuFeatureRelevanceRepository,
      FeatureService featureService
  ) {
    this.roleService = roleService;
    this.permissionsService = permissionsService;
    this.roleRepository = roleRepository;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.dataScopeRepository = dataScopeRepository;
    this.fieldScopeRepository = fieldScopeRepository;
    this.menuRepository = menuRepository;
    this.menuFeatureRelevanceRepository = menuFeatureRelevanceRepository;
    this.featureService = featureService;
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
  public List<AccessCenterResourceNodeVo> resourceTree(String roleId) {
    if (!hasText(roleId)) {
      throw new IllegalArgumentException("roleId is required");
    }
    Set<String> authorizedAuthorities = normalizeAuthorities(roleService.authorized(roleId));
    List<PermissionsRelevanceVo> permissions = loadVisiblePermissionItems();
    Map<String, PermissionsRelevanceVo> permissionMap = permissions.stream()
        .filter(permission -> hasText(permission.getAuthority()))
        .collect(Collectors.toMap(
            permission -> permission.getAuthority().trim(),
            Function.identity(),
            (left, right) -> left,
            HashMap::new
        ));

    Collection<Menu> menus = menuRepository.loadAll();
    Map<String, Menu> menuMap = menus.stream()
        .filter(menu -> hasText(menu.getId()))
        .collect(Collectors.toMap(Menu::getId, Function.identity(), (left, right) -> left, HashMap::new));
    Map<String, List<Menu>> childrenByParent = menus.stream()
        .filter(menu -> hasText(menu.getParent()) && menuMap.containsKey(menu.getParent()))
        .collect(Collectors.groupingBy(Menu::getParent, HashMap::new, Collectors.toCollection(ArrayList::new)));
    Map<String, Set<String>> featureCodesByMenuId = loadFeatureCodesByMenuId(menus);
    Map<String, Feature> featureMap = loadFeatureMap(featureCodesByMenuId);
    Map<String, List<AccessCenterResourceNodeVo>> featureNodesByMenuId = buildFeatureNodesByMenuId(
        featureCodesByMenuId,
        featureMap,
        permissionMap,
        authorizedAuthorities
    );

    List<AccessCenterResourceNodeVo> roots = menus.stream()
        .filter(menu -> !hasText(menu.getParent()) || !menuMap.containsKey(menu.getParent()))
        .sorted(this::compareMenus)
        .map(menu -> buildMenuNode(menu, childrenByParent, featureNodesByMenuId, authorizedAuthorities))
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toCollection(ArrayList::new));

    Set<String> groupedAuthorities = roots.stream()
        .flatMap(node -> node.getPermissionAuthorities().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    List<AccessCenterResourceNodeVo> unclassified = permissions.stream()
        .filter(permission -> hasText(permission.getAuthority()))
        .filter(permission -> !groupedAuthorities.contains(permission.getAuthority()))
        .sorted(this::comparePermissions)
        .map(permission -> buildPermissionNode("group:unclassified", permission, authorizedAuthorities))
        .toList();
    if (!unclassified.isEmpty()) {
      roots.add(buildGroupNode("group:unclassified", "UNCLASSIFIED", "未归类权限", unclassified, authorizedAuthorities));
    }
    return roots;
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

  private List<PermissionsRelevanceVo> loadVisiblePermissionItems() {
    return permissionsService.permissionItems(Pageable.unpaged()).getContent().stream()
        .filter(permission -> hasText(permission.getAuthority()))
        .sorted(this::comparePermissions)
        .toList();
  }

  private Map<String, Set<String>> loadFeatureCodesByMenuId(Collection<Menu> menus) {
    Map<String, Set<String>> result = new HashMap<>();
    for (Menu menu : menus) {
      if (!hasText(menu.getId())) {
        continue;
      }
      Set<String> featureCodes = normalizeAuthorities(menuFeatureRelevanceRepository.authorized(menu.getId()));
      if (!featureCodes.isEmpty()) {
        result.put(menu.getId(), featureCodes);
      }
    }
    return result;
  }

  private Map<String, Feature> loadFeatureMap(Map<String, Set<String>> featureCodesByMenuId) {
    Set<String> featureCodes = featureCodesByMenuId.values().stream()
        .flatMap(Set::stream)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (featureCodes.isEmpty()) {
      return Map.of();
    }
    return featureService.findAllByCodes(featureCodes).stream()
        .filter(feature -> hasText(feature.getCode()))
        .collect(Collectors.toMap(Feature::getCode, Function.identity(), (left, right) -> left, HashMap::new));
  }

  private Map<String, List<AccessCenterResourceNodeVo>> buildFeatureNodesByMenuId(
      Map<String, Set<String>> featureCodesByMenuId,
      Map<String, Feature> featureMap,
      Map<String, PermissionsRelevanceVo> permissionMap,
      Set<String> authorizedAuthorities
  ) {
    Map<String, List<AccessCenterResourceNodeVo>> result = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : featureCodesByMenuId.entrySet()) {
      List<AccessCenterResourceNodeVo> featureNodes = entry.getValue().stream()
          .map(featureMap::get)
          .filter(java.util.Objects::nonNull)
          .sorted(this::compareFeatures)
          .map(feature -> buildFeatureNode(feature, permissionMap, authorizedAuthorities))
          .filter(java.util.Objects::nonNull)
          .toList();
      if (!featureNodes.isEmpty()) {
        result.put(entry.getKey(), featureNodes);
      }
    }
    return result;
  }

  private AccessCenterResourceNodeVo buildMenuNode(
      Menu menu,
      Map<String, List<Menu>> childrenByParent,
      Map<String, List<AccessCenterResourceNodeVo>> featureNodesByMenuId,
      Set<String> authorizedAuthorities
  ) {
    List<AccessCenterResourceNodeVo> children = new ArrayList<>();
    childrenByParent.getOrDefault(menu.getId(), List.of()).stream()
        .sorted(this::compareMenus)
        .map(child -> buildMenuNode(child, childrenByParent, featureNodesByMenuId, authorizedAuthorities))
        .filter(java.util.Objects::nonNull)
        .forEach(children::add);
    children.addAll(featureNodesByMenuId.getOrDefault(menu.getId(), List.of()));
    if (children.isEmpty()) {
      return null;
    }
    AccessCenterResourceNodeVo node = new AccessCenterResourceNodeVo();
    node.setId("menu:" + menu.getId());
    node.setType(NODE_TYPE_MENU);
    node.setLabel(firstNonBlank(menu.getLabel(), menu.getTitle(), menu.getAuthority(), menu.getPath(), menu.getId()));
    node.setCode(firstNonBlank(menu.getAuthority(), menu.getPath()));
    node.setPath(menu.getPath());
    node.setDescription(menu.getTitle());
    node.setChildren(children);
    applyAuthorizationState(node, authorizedAuthorities);
    return node;
  }

  private AccessCenterResourceNodeVo buildFeatureNode(
      Feature feature,
      Map<String, PermissionsRelevanceVo> permissionMap,
      Set<String> authorizedAuthorities
  ) {
    String nodeId = "feature:" + feature.getCode();
    List<AccessCenterResourceNodeVo> children = normalizeAuthorities(featureService.authorizedPermissions(feature.getCode())).stream()
        .map(permissionMap::get)
        .filter(java.util.Objects::nonNull)
        .sorted(this::comparePermissions)
        .map(permission -> buildPermissionNode(nodeId, permission, authorizedAuthorities))
        .toList();
    if (children.isEmpty()) {
      return null;
    }
    AccessCenterResourceNodeVo node = new AccessCenterResourceNodeVo();
    node.setId(nodeId);
    node.setType(NODE_TYPE_FEATURE);
    node.setLabel(firstNonBlank(feature.getName(), feature.getCode()));
    node.setCode(feature.getCode());
    node.setDescription(feature.getDescription());
    node.setChildren(children);
    applyAuthorizationState(node, authorizedAuthorities);
    return node;
  }

  private AccessCenterResourceNodeVo buildPermissionNode(
      String parentNodeId,
      PermissionsRelevanceVo permission,
      Set<String> authorizedAuthorities
  ) {
    String authority = permission.getAuthority().trim();
    AccessCenterResourceNodeVo node = new AccessCenterResourceNodeVo();
    node.setId(parentNodeId + ":permission:" + authority);
    node.setType(NODE_TYPE_PERMISSION);
    node.setLabel(firstNonBlank(permission.getName(), authority));
    node.setCode(authority);
    node.setDescription(permission.getDescription());
    node.setPermissionAuthority(authority);
    node.setPermissionType(permission.getType());
    node.setPermissionAuthorities(new LinkedHashSet<>(Set.of(authority)));
    node.setChecked(authorizedAuthorities.contains(authority));
    node.setPartial(false);
    return node;
  }

  private AccessCenterResourceNodeVo buildGroupNode(
      String id,
      String code,
      String label,
      List<AccessCenterResourceNodeVo> children,
      Set<String> authorizedAuthorities
  ) {
    AccessCenterResourceNodeVo node = new AccessCenterResourceNodeVo();
    node.setId(id);
    node.setType(NODE_TYPE_GROUP);
    node.setCode(code);
    node.setLabel(label);
    node.setChildren(children);
    applyAuthorizationState(node, authorizedAuthorities);
    return node;
  }

  private void applyAuthorizationState(
      AccessCenterResourceNodeVo node,
      Set<String> authorizedAuthorities
  ) {
    Set<String> authorities = node.getChildren().stream()
        .flatMap(child -> child.getPermissionAuthorities().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (hasText(node.getPermissionAuthority())) {
      authorities.add(node.getPermissionAuthority());
    }
    node.setPermissionAuthorities(authorities);
    long authorizedCount = authorities.stream().filter(authorizedAuthorities::contains).count();
    node.setChecked(!authorities.isEmpty() && authorizedCount == authorities.size());
    node.setPartial(authorizedCount > 0 && authorizedCount < authorities.size());
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

  private int compareMenus(Menu left, Menu right) {
    return Comparator
        .comparing((Menu menu) -> menu.getSort(), Comparator.nullsLast(Integer::compareTo))
        .thenComparing(menu -> firstNonBlank(menu.getLabel(), menu.getTitle(), menu.getAuthority(), menu.getPath(), menu.getId()))
        .compare(left, right);
  }

  private int compareFeatures(Feature left, Feature right) {
    return Comparator
        .comparing((Feature feature) -> feature.getSort(), Comparator.nullsLast(Integer::compareTo))
        .thenComparing(feature -> firstNonBlank(feature.getName(), feature.getCode()))
        .compare(left, right);
  }

  private int comparePermissions(PermissionsRelevanceVo left, PermissionsRelevanceVo right) {
    return Comparator
        .comparing((PermissionsRelevanceVo permission) -> permission.getType(), Comparator.nullsLast(Integer::compareTo))
        .thenComparing(permission -> firstNonBlank(permission.getName(), permission.getAuthority()))
        .compare(left, right);
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
