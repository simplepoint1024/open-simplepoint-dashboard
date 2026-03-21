package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service implementation.
 */
@Service
public class ApplicationServiceImpl
    extends BaseServiceImpl<ApplicationRepository, Application, String>
    implements ApplicationService {

  private final ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;
  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final TenantRepository tenantRepository;

  public ApplicationServiceImpl(
      ApplicationRepository repository,
      DetailsProviderService detailsProviderService,
      ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository
  ) {
    super(repository, detailsProviderService);
    this.applicationFeatureRelevanceRepository = applicationFeatureRelevanceRepository;
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
  }

  @Override
  public Collection<String> authorizedFeatures(String applicationCode) {
    return applicationFeatureRelevanceRepository.authorized(requireCode(applicationCode, "应用编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<ApplicationFeatureRelevance> authorizeFeatures(ApplicationFeaturesRelevanceDto dto) {
    String applicationCode = requireCode(dto.getApplicationCode(), "应用编码不能为空");
    Set<String> featureCodes = normalizeCodes(dto.getFeatureCodes());
    if (featureCodes.isEmpty()) {
      return List.of();
    }

    Set<ApplicationFeatureRelevance> relations = new LinkedHashSet<>(featureCodes.size());
    for (String featureCode : featureCodes) {
      ApplicationFeatureRelevance relevance = new ApplicationFeatureRelevance();
      relevance.setApplicationCode(applicationCode);
      relevance.setFeatureCode(featureCode);
      relations.add(relevance);
    }

    Collection<ApplicationFeatureRelevance> saved = applicationFeatureRelevanceRepository.saveAll(relations);
    refreshTenantsByApplicationCodes(Set.of(applicationCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedFeatures(String applicationCode, Set<String> featureCodes) {
    Set<String> normalizedFeatureCodes = normalizeCodes(featureCodes);
    if (normalizedFeatureCodes.isEmpty()) {
      return;
    }
    String requiredApplicationCode = requireCode(applicationCode, "应用编码不能为空");
    applicationFeatureRelevanceRepository.unauthorized(requiredApplicationCode, normalizedFeatureCodes);
    refreshTenantsByApplicationCodes(Set.of(requiredApplicationCode));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Application> Application modifyById(S entity) {
    Application current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("应用不存在"));
    String oldCode = current.getCode();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(Set.of(oldCode));
    Application updated = (Application) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
      applicationFeatureRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
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

    Set<String> applicationCodes = findAllByIds(ids).stream()
        .map(Application::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (applicationCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(applicationCodes);
    applicationFeatureRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
    packageApplicationRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
    super.removeByIds(ids);
    refreshPermissionVersion(affectedTenantIds);
  }

  private void refreshTenantsByApplicationCodes(Collection<String> applicationCodes) {
    refreshPermissionVersion(tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(applicationCodes));
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
