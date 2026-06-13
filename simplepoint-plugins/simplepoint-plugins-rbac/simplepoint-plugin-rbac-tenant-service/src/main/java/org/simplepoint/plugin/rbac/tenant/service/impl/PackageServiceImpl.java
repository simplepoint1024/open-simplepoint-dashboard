package org.simplepoint.plugin.rbac.tenant.service.impl;

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
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PackageService;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package service implementation.
 */
@Service
public class PackageServiceImpl
    extends BaseServiceImpl<PackageRepository, Package, String>
    implements PackageService {

  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final TenantRepository tenantRepository;
  private final PermissionVersionRefreshService permissionVersionRefreshService;

  /**
   * Package Service Impl.
   */
  public PackageServiceImpl(
      PackageRepository repository,
      DetailsProviderService detailsProviderService,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository,
      PermissionVersionRefreshService permissionVersionRefreshService
  ) {
    super(repository, detailsProviderService);
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.permissionVersionRefreshService = permissionVersionRefreshService;
  }

  @Override
  public <S extends Package> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return super.limit(attributes, pageable);
    }
    Collection<String> packageCodes = tenantPackageRelevanceRepository.authorized(resolveTenantScope());
    if (packageCodes.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("code", "in:" + String.join(",", packageCodes));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<Package> findById(String id) {
    Optional<Package> pkg = super.findById(id);
    if (pkg.isEmpty() || AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return pkg;
    }
    Collection<String> packageCodes = tenantPackageRelevanceRepository.authorized(resolveTenantScope());
    return packageCodes.contains(pkg.get().getCode()) ? pkg : Optional.empty();
  }

  @Override
  public Collection<String> authorizedApplications(String packageCode) {
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())
        && !tenantPackageRelevanceRepository.authorized(resolveTenantScope()).contains(packageCode)) {
      throw new IllegalArgumentException("套餐不存在或不属于当前租户");
    }
    return packageApplicationRelevanceRepository.authorized(requireCode(packageCode, "套餐编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<PackageApplicationRelevance> authorizeApplications(PackageApplicationsRelevanceDto dto) {
    requirePlatformAdministrator();
    String packageCode = requireCode(dto.getPackageCode(), "套餐编码不能为空");
    Set<String> applicationCodes = normalizeCodes(dto.getApplicationCodes());
    if (applicationCodes.isEmpty()) {
      return List.of();
    }

    Set<PackageApplicationRelevance> relations = new LinkedHashSet<>(applicationCodes.size());
    for (String applicationCode : applicationCodes) {
      PackageApplicationRelevance relevance = new PackageApplicationRelevance();
      relevance.setPackageCode(packageCode);
      relevance.setApplicationCode(applicationCode);
      relations.add(relevance);
    }

    Collection<PackageApplicationRelevance> saved = packageApplicationRelevanceRepository.saveAll(relations);
    permissionVersionRefreshService.refreshByPackageCodes(Set.of(packageCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedApplications(String packageCode, Set<String> applicationCodes) {
    requirePlatformAdministrator();
    Set<String> normalizedApplicationCodes = normalizeCodes(applicationCodes);
    if (normalizedApplicationCodes.isEmpty()) {
      return;
    }
    String requiredPackageCode = requireCode(packageCode, "套餐编码不能为空");
    packageApplicationRelevanceRepository.unauthorized(requiredPackageCode, normalizedApplicationCodes);
    permissionVersionRefreshService.refreshByPackageCodes(Set.of(requiredPackageCode));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Package> Package modifyById(S entity) {
    requirePlatformAdministrator();
    Package current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("套餐不存在"));
    String oldCode = current.getCode();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(Set.of(oldCode));
    Package updated = (Package) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updatePackageCode(oldCode, updated.getCode());
      tenantPackageRelevanceRepository.updatePackageCode(oldCode, updated.getCode());
      permissionVersionRefreshService.refreshTenants(affectedTenantIds);
    }
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    requirePlatformAdministrator();
    if (ids == null || ids.isEmpty()) {
      return;
    }

    Set<String> packageCodes = findAllByIds(ids).stream()
        .map(Package::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (packageCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    final Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(packageCodes);
    packageApplicationRelevanceRepository.deleteAllByPackageCodes(packageCodes);
    tenantPackageRelevanceRepository.deleteAllByPackageCodes(packageCodes);
    super.removeByIds(ids);
    permissionVersionRefreshService.refreshTenants(affectedTenantIds);
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Set.of();
    }
    return codes.stream()
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
  }

  private static String requireCode(String code, String message) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return code;
  }

  private void requirePlatformAdministrator() {
    AuthorizationScopeGuards.requirePlatformAdministrator(getAuthorizationContext());
  }

  private String resolveTenantScope() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    var ctx = getAuthorizationContext();
    String userId = ctx != null ? ctx.getUserId() : null;
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException("Tenant context is required");
    }
    return tenantRepository.findPersonalTenantByOwnerId(userId)
        .map(Tenant::getId)
        .orElseThrow(() -> new IllegalStateException("Tenant context is required"));
  }
}
