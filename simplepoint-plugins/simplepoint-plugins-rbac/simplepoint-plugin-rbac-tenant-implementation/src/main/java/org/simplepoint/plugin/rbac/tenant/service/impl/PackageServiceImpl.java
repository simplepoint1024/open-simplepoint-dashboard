package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PackageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Package service implementation. */
@Service
public class PackageServiceImpl extends BaseServiceImpl<PackageRepository, Package, String>
    implements PackageService {

  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;

  /** Package Service Impl. */
  public PackageServiceImpl(
      PackageRepository repository,
      DetailsProviderService detailsProviderService,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository) {
    super(repository, detailsProviderService);
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
  }

  @Override
  public Map<String, Object> schema() {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.schema();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Package> S create(S entity) {
    requirePlatformAdministrator();
    return super.create(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Package> create(Collection<Package> entities) {
    requirePlatformAdministrator();
    return super.create(entities);
  }

  @Override
  public List<Package> findAllByIds(Iterable<String> ids) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.findAllByIds(ids);
  }

  @Override
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(Class<Package> domainClass) {
    return org.simplepoint.core.AuthorizationScopeGuards.isPlatformAdministrator(
            getAuthorizationContext())
        ? super.getButtonDeclarationsSchema(domainClass)
        : Set.of();
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends Package> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.limit(attributes, pageable);
  }

  @Override
  public Page<Package> assignmentItems(
      String parent, boolean selected, Map<String, String> filters, Pageable pageable) {
    throw new org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException(
        org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code
            .LEGACY_ASSIGNMENT_RETIRED);
  }

  @Override
  public Optional<Package> findById(String id) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.findById(id);
  }

  @Override
  public Collection<String> authorizedApplications(String packageCode) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return packageApplicationRelevanceRepository.authorized(requireCode(packageCode, "套餐编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<PackageApplicationRelevance> authorizeApplications(
      PackageApplicationsRelevanceDto dto) {
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

    Collection<PackageApplicationRelevance> saved =
        packageApplicationRelevanceRepository.saveAll(relations);
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
    packageApplicationRelevanceRepository.unauthorized(
        requiredPackageCode, normalizedApplicationCodes);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Package> Package modifyById(S entity) {
    requirePlatformAdministrator();
    Package current =
        findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("套餐不存在"));
    String oldCode = current.getCode();
    Package updated = (Package) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updatePackageCode(oldCode, updated.getCode());
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

    Set<String> packageCodes =
        findAllByIds(ids).stream()
            .map(Package::getCode)
            .filter(code -> code != null && !code.isBlank())
            .collect(Collectors.toSet());
    if (packageCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    packageApplicationRelevanceRepository.deleteAllByPackageCodes(packageCodes);
    super.removeByIds(ids);
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
    PlatformTemplateBoundary.write(getAuthorizationContext());
  }
}
