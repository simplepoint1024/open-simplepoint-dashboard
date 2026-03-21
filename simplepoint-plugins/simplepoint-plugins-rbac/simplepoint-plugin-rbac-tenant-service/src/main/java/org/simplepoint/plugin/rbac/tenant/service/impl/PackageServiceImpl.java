package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PackageService;
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

  public PackageServiceImpl(
      PackageRepository repository,
      DetailsProviderService detailsProviderService,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository
  ) {
    super(repository, detailsProviderService);
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
  }

  @Override
  public Collection<String> authorizedApplications(String packageCode) {
    return packageApplicationRelevanceRepository.authorized(requireCode(packageCode, "套餐编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<PackageApplicationRelevance> authorizeApplications(PackageApplicationsRelevanceDto dto) {
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
    refreshTenantsByPackageCodes(Set.of(packageCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedApplications(String packageCode, Set<String> applicationCodes) {
    Set<String> normalizedApplicationCodes = normalizeCodes(applicationCodes);
    if (normalizedApplicationCodes.isEmpty()) {
      return;
    }
    String requiredPackageCode = requireCode(packageCode, "套餐编码不能为空");
    packageApplicationRelevanceRepository.unauthorized(requiredPackageCode, normalizedApplicationCodes);
    refreshTenantsByPackageCodes(Set.of(requiredPackageCode));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Package> Package modifyById(S entity) {
    Package current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("套餐不存在"));
    String oldCode = current.getCode();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(Set.of(oldCode));
    Package updated = (Package) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updatePackageCode(oldCode, updated.getCode());
      tenantPackageRelevanceRepository.updatePackageCode(oldCode, updated.getCode());
      refreshPermissionVersion(affectedTenantIds);
    }
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
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

    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(packageCodes);
    packageApplicationRelevanceRepository.deleteAllByPackageCodes(packageCodes);
    tenantPackageRelevanceRepository.deleteAllByPackageCodes(packageCodes);
    super.removeByIds(ids);
    refreshPermissionVersion(affectedTenantIds);
  }

  private void refreshTenantsByPackageCodes(Collection<String> packageCodes) {
    refreshPermissionVersion(tenantPackageRelevanceRepository.findTenantIdsByPackageCodes(packageCodes));
  }

  private void refreshPermissionVersion(Collection<String> tenantIds) {
    if (tenantIds == null || tenantIds.isEmpty()) {
      return;
    }
    tenantRepository.increasePermissionVersion(tenantIds);
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
}
