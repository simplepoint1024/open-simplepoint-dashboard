package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant service implementation.
 */
@Service
@RemoteProvider
public class TenantServiceImpl extends BaseServiceImpl<TenantRepository, Tenant, String> implements TenantService {

  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final TenantUserRelevanceRepository tenantUserRelevanceRepository;
  private final ResourceAuthorizationVersionService resourceAuthorizationVersionService;
  private final UsersService usersService;

  /**
   * Tenant Service Impl.
   */
  public TenantServiceImpl(
      TenantRepository repository,
      DetailsProviderService detailsProviderService,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantUserRelevanceRepository tenantUserRelevanceRepository,
      ResourceAuthorizationVersionService resourceAuthorizationVersionService,
      UsersService usersService
  ) {
    super(repository, detailsProviderService);
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantUserRelevanceRepository = tenantUserRelevanceRepository;
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
    this.usersService = usersService;
  }

  @Override
  public <S extends Tenant> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (isSuperAdministrator()) {
      return super.limit(attributes, pageable);
    }
    Set<String> tenantIds = getCurrentUserTenants().stream()
        .map(NamedTenantVo::tenantId)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (tenantIds.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("id", "in:" + String.join(",", tenantIds));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<Tenant> findById(String id) {
    Optional<Tenant> tenant = super.findById(id);
    if (tenant.isEmpty() || isSuperAdministrator()) {
      return tenant;
    }
    Authentication authentication = getRequiredAuthentication();
    return getRepository().hasUser(id, authentication.getName()) ? tenant : Optional.empty();
  }

  @Override
  public Set<NamedTenantVo> getTenantsByUserId(String userId) {
    return getRepository().getTenantsByUserId(userId);
  }

  @Override
  public Set<NamedTenantVo> getCurrentUserTenants() {
    Authentication authentication = getRequiredAuthentication();
    Tenant personalTenant = ensurePersonalTenantExists(authentication.getName());
    Set<NamedTenantVo> tenants = new LinkedHashSet<>(this.getTenantsByUserId(authentication.getName()));
    if (personalTenant != null) {
      tenants.add(new NamedTenantVo(personalTenant.getId(), personalTenant.getName(), personalTenant.getTenantType()));
    }
    return tenants;
  }

  @Override
  public Collection<RoleGrantedAuthority> getCurrentUserRoles(String tenantId) {
    Authentication authentication = getRequiredAuthentication();
    String resolvedTenantId = resolveRequestedTenantId(tenantId, authentication);
    return loadEffectiveRoles(resolvedTenantId, authentication.getName());
  }

  @Override
  public String calculateAuthorizationContextId(String tenantId, String roleId) {
    Authentication authentication = getRequiredAuthentication();
    String resolvedTenantId = resolveRequestedTenantId(tenantId, authentication);
    String normalizedRoleId = trimToNull(roleId);
    if (normalizedRoleId != null) {
      boolean roleAllowed = loadEffectiveRoles(resolvedTenantId, authentication.getName()).stream()
          .anyMatch(role -> normalizedRoleId.equals(role.getId()));
      if (!roleAllowed) {
        throw new AccessDeniedException("当前用户未拥有指定角色");
      }
    }
    Long authorizationVersion = 0L;
    if (resolvedTenantId != null && !resolvedTenantId.isBlank()) {
      authorizationVersion = getRepository().getTenantAuthorizationVersion(resolvedTenantId);
    }
    if (authorizationVersion == null) {
      authorizationVersion = 0L;
    }
    return sha256((resolvedTenantId != null ? resolvedTenantId : "")
        + ":" + authentication.getName()
        + ":" + authorizationVersion
        + (normalizedRoleId == null ? "" : ":" + normalizedRoleId));
  }

  private String resolveRequestedTenantId(String tenantId, Authentication authentication) {
    String normalizedTenantId = normalizeTenantId(tenantId);
    if (normalizedTenantId != null && !getRepository().hasUser(normalizedTenantId, authentication.getName())) {
      throw new AccessDeniedException("当前用户未加入指定租户");
    }
    return normalizedTenantId == null
        ? Optional.ofNullable(ensurePersonalTenantExists(authentication.getName()))
            .map(Tenant::getId)
            .orElse(null)
        : normalizedTenantId;
  }

  private String normalizeTenantId(String tenantId) {
    if (tenantId == null) {
      return null;
    }
    String trimmed = tenantId.trim();
    if (trimmed.isEmpty() || "default".equals(trimmed)) {
      return null;
    }
    return trimmed;
  }

  @Override
  public Collection<String> authorizedPackages(String tenantId) {
    Tenant tenant = requireTenant(tenantId);
    requirePlatformAdministrator();
    return tenantPackageRelevanceRepository.authorized(tenant.getId());
  }

  @Override
  public Page<UserRelevanceVo> ownerItems(Pageable pageable) {
    return tenantUserRelevanceRepository.items(pageable);
  }

  @Override
  public Page<UserRelevanceVo> userItems(String tenantId, Pageable pageable) {
    requireTenantMemberManager(tenantId);
    return tenantUserRelevanceRepository.items(pageable);
  }

  @Override
  public Collection<String> authorizedUsers(String tenantId) {
    Tenant tenant = requireTenantMemberManager(tenantId);
    LinkedHashSet<String> userIds = new LinkedHashSet<>(tenantUserRelevanceRepository.authorized(tenant.getId()));
    if (tenant.getOwnerId() != null && !tenant.getOwnerId().isBlank()) {
      userIds.add(tenant.getOwnerId());
    }
    return userIds;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<TenantPackageRelevance> authorizePackages(TenantPackagesRelevanceDto dto) {
    String tenantId = requireTenant(dto.getTenantId()).getId();
    requirePlatformAdministrator();
    Set<String> packageCodes = normalizeCodes(dto.getPackageCodes());
    if (packageCodes.isEmpty()) {
      return List.of();
    }

    Set<TenantPackageRelevance> relations = new LinkedHashSet<>(packageCodes.size());
    for (String packageCode : packageCodes) {
      TenantPackageRelevance relevance = new TenantPackageRelevance();
      relevance.setTenantId(tenantId);
      relevance.setPackageCode(packageCode);
      relations.add(relevance);
    }

    Collection<TenantPackageRelevance> saved = tenantPackageRelevanceRepository.saveAll(relations);
    resourceAuthorizationVersionService.refreshTenant(tenantId);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedPackages(String tenantId, Set<String> packageCodes) {
    Set<String> normalizedPackageCodes = normalizeCodes(packageCodes);
    if (normalizedPackageCodes.isEmpty()) {
      return;
    }
    String requiredTenantId = requireTenant(tenantId).getId();
    requirePlatformAdministrator();
    tenantPackageRelevanceRepository.unauthorized(requiredTenantId, normalizedPackageCodes);
    resourceAuthorizationVersionService.refreshTenant(requiredTenantId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<TenantUserRelevance> authorizeUsers(TenantUsersRelevanceDto dto) {
    Tenant tenant = requireTenantMemberManager(dto.getTenantId());
    Set<String> userIds = normalizeCodes(dto.getUserIds());
    if (userIds.isEmpty()) {
      return List.of();
    }
    validateUsersExist(userIds);

    LinkedHashSet<String> existingUserIds = new LinkedHashSet<>(tenantUserRelevanceRepository.authorized(tenant.getId()));
    LinkedHashSet<TenantUserRelevance> relations = new LinkedHashSet<>();
    for (String userId : userIds) {
      if (!existingUserIds.add(userId)) {
        continue;
      }
      TenantUserRelevance relevance = new TenantUserRelevance();
      relevance.setTenantId(tenant.getId());
      relevance.setUserId(userId);
      relations.add(relevance);
    }

    if (relations.isEmpty()) {
      return List.of();
    }

    Collection<TenantUserRelevance> saved = tenantUserRelevanceRepository.saveAll(relations);
    resourceAuthorizationVersionService.refreshTenant(tenant.getId());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedUsers(String tenantId, Set<String> userIds) {
    Tenant tenant = requireTenantMemberManager(tenantId);
    Set<String> normalizedUserIds = normalizeCodes(userIds);
    if (normalizedUserIds.isEmpty()) {
      return;
    }
    if (tenant.getOwnerId() != null && normalizedUserIds.contains(tenant.getOwnerId())) {
      throw new IllegalArgumentException("租户所有者不能移出租户成员");
    }
    tenantUserRelevanceRepository.unauthorized(tenant.getId(), normalizedUserIds);
    resourceAuthorizationVersionService.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Tenant> S create(S entity) {
    requirePlatformAdministrator();
    Authentication authentication = getRequiredAuthentication();
    if (entity.getAuthorizationVersion() == null) {
      entity.setAuthorizationVersion(0L);
    }
    if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
      entity.setOwnerId(authentication.getName());
    }
    validateUsersExist(Set.of(entity.getOwnerId()));
    S created = super.create(entity);
    ensureOwnerMembership(created.getId(), created.getOwnerId());
    return created;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Tenant> Tenant modifyById(S entity) {
    requirePlatformAdministrator();
    Tenant current = super.findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
    if (entity.getAuthorizationVersion() == null) {
      entity.setAuthorizationVersion(current.getAuthorizationVersion());
    }
    if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
      entity.setOwnerId(current.getOwnerId());
    }
    validateUsersExist(Set.of(entity.getOwnerId()));
    boolean ownerChanged = !Objects.equals(current.getOwnerId(), entity.getOwnerId());
    Tenant modified = super.modifyById(entity);
    boolean ownerMembershipAdded = ensureOwnerMembership(modified.getId(), modified.getOwnerId());
    if (ownerChanged || ownerMembershipAdded) {
      resourceAuthorizationVersionService.refreshTenant(modified.getId());
    }
    return modified;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    requirePlatformAdministrator();
    if (ids == null || ids.isEmpty()) {
      return;
    }
    tenantPackageRelevanceRepository.deleteAllByTenantIds(ids);
    tenantUserRelevanceRepository.deleteAllByTenantIds(ids);
    super.removeByIds(ids);
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Authentication getRequiredAuthentication() {
    SecurityContext context = SecurityContextHolder.getContext();
    Authentication authentication = context == null ? null : context.getAuthentication();
    if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
      throw new IllegalStateException("当前未认证用户");
    }
    return authentication;
  }

  private Tenant requireTenantMemberManager(String tenantId) {
    Tenant tenant = requireTenant(tenantId);
    Authentication authentication = getRequiredAuthentication();
    if (tenant.getTenantType() == org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL) {
      throw new AccessDeniedException("个人空间不支持组织成员管理");
    }
    AuthorizationContext context = getAuthorizationContext();
    if (AuthorizationScopeGuards.isTenantManager(context, tenant.getOwnerId())
        || isAdministrator(authentication)
        || isTenantAdmin(authentication)
        || Objects.equals(authentication.getName(), tenant.getOwnerId())) {
      return tenant;
    }
    throw new AccessDeniedException("仅管理员或租户所有者可以配置租户成员");
  }

  private Tenant requireTenant(String tenantId) {
    String requiredTenantId = requireTenantId(tenantId);
    return super.findById(requiredTenantId).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
  }

  private String resolveCurrentTenantScope() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    Authentication authentication = getRequiredAuthentication();
    return resolveTenantId(null, authentication.getName());
  }

  private void requirePlatformAdministrator() {
    AuthorizationContext context = getAuthorizationContext();
    if (isSuperAdministrator()) {
      return;
    }
    AuthorizationScopeGuards.requirePlatformAdministrator(context);
  }

  private boolean isSuperAdministrator() {
    AuthorizationContext context = getAuthorizationContext();
    if (context != null && Boolean.TRUE.equals(context.getIsAdministrator())) {
      return true;
    }
    return isAdministrator(getRequiredAuthentication());
  }

  private boolean ensureOwnerMembership(String tenantId, String ownerId) {
    if (ownerId == null || ownerId.isBlank()) {
      return false;
    }
    Collection<String> authorizedUserIds = tenantUserRelevanceRepository.authorized(tenantId);
    Set<String> existingUserIds = new LinkedHashSet<>(authorizedUserIds == null ? List.of() : authorizedUserIds);
    if (existingUserIds.contains(ownerId)) {
      return false;
    }
    TenantUserRelevance relevance = new TenantUserRelevance();
    relevance.setTenantId(tenantId);
    relevance.setUserId(ownerId);
    tenantUserRelevanceRepository.saveAll(List.of(relevance));
    return true;
  }

  private Tenant ensurePersonalTenantExists(String userId) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    Optional<Tenant> existing = Optional.ofNullable(getRepository().findPersonalTenantByOwnerId(userId))
        .orElse(Optional.empty());
    if (existing.isPresent()) {
      Tenant tenant = existing.get();
      ensureOwnerMembership(tenant.getId(), userId);
      return tenant;
    }
    Tenant tenant = new Tenant();
    tenant.setName(userId + " 的个人空间");
    tenant.setDescription("个人租户");
    tenant.setOwnerId(userId);
    tenant.setTenantType(org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL);
    tenant.setAuthorizationVersion(0L);
    Tenant saved = getRepository().save(tenant);
    ensureOwnerMembership(saved.getId(), userId);
    return saved;
  }

  private void validateUsersExist(Set<String> userIds) {
    Set<String> normalizedUserIds = normalizeCodes(userIds);
    if (normalizedUserIds.isEmpty()) {
      throw new IllegalArgumentException("用户ID不能为空");
    }
    Set<String> existingUserIds = tenantUserRelevanceRepository.existingUserIds(normalizedUserIds);
    if (existingUserIds.size() == normalizedUserIds.size()) {
      return;
    }
    LinkedHashSet<String> missingUserIds = new LinkedHashSet<>(normalizedUserIds);
    missingUserIds.removeAll(existingUserIds);
    throw new IllegalArgumentException("用户不存在: " + String.join(",", missingUserIds));
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Set.of();
    }
    return codes.stream()
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
  }

  private Collection<RoleGrantedAuthority> loadEffectiveRoles(String tenantId, String userId) {
    Map<String, RoleGrantedAuthority> rolesById = new java.util.LinkedHashMap<>();
    usersService.loadRolesByUserId(tenantId, userId).stream()
        .filter(role -> role.getId() != null && !role.getId().isBlank())
        .forEach(role -> rolesById.putIfAbsent(role.getId(), role));
    if (tenantId != null && !tenantId.isBlank()) {
      usersService.loadRolesByUserId(null, userId).stream()
          .filter(role -> role.getId() != null && !role.getId().isBlank())
          .forEach(role -> rolesById.putIfAbsent(role.getId(), role));
    }
    return List.copyOf(rolesById.values());
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("租户ID不能为空");
    }
    return tenantId;
  }

  private String resolveTenantId(String tenantId, String userId) {
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }

    // Prefer org tenants (sorted first) then personal tenant
    return getTenantsByUserId(userId).stream()
        .map(NamedTenantVo::tenantId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static boolean isAdministrator(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_Administrator".equals(authority.getAuthority()));
  }

  private static boolean isTenantAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> "tenant.admin".equals(authority.getAuthority())
            || "ROLE_TENANT_ADMIN".equals(authority.getAuthority()));
  }
}
