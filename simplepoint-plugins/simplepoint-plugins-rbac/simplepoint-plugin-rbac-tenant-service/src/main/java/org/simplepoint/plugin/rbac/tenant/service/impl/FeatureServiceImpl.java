package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature service implementation.
 */
@Service
public class FeatureServiceImpl extends BaseServiceImpl<FeatureRepository, Feature, String> implements FeatureService {

  private final FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;
  private final ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final TenantRepository tenantRepository;

  public FeatureServiceImpl(
      FeatureRepository repository,
      DetailsProviderService detailsProviderService,
      FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository,
      ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository
  ) {
    super(repository, detailsProviderService);
    this.featurePermissionRelevanceRepository = featurePermissionRelevanceRepository;
    this.applicationFeatureRelevanceRepository = applicationFeatureRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
  }

  @Override
  public Collection<String> authorizedPermissions(String featureCode) {
    return featurePermissionRelevanceRepository.authorized(requireCode(featureCode, "功能编码不能为空"));
  }

  @Override
  public Collection<Feature> findAllByCodes(Collection<String> featureCodes) {
    Set<String> normalizedCodes = normalizeCodes(featureCodes);
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    return getRepository().findAllByCodes(normalizedCodes);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<FeaturePermissionRelevance> authorizePermissions(FeaturePermissionsRelevanceDto dto) {
    String featureCode = requireCode(dto.getFeatureCode(), "功能编码不能为空");
    Set<String> permissionAuthorities = normalizeCodes(dto.getPermissionAuthority());
    if (permissionAuthorities.isEmpty()) {
      return List.of();
    }

    Set<FeaturePermissionRelevance> relations = new LinkedHashSet<>(permissionAuthorities.size());
    for (String permissionAuthority : permissionAuthorities) {
      FeaturePermissionRelevance relevance = new FeaturePermissionRelevance();
      relevance.setFeatureCode(featureCode);
      relevance.setPermissionAuthority(permissionAuthority);
      relations.add(relevance);
    }

    Collection<FeaturePermissionRelevance> saved = featurePermissionRelevanceRepository.saveAll(relations);
    refreshTenantsByFeatureCodes(Set.of(featureCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedPermissions(String featureCode, Set<String> permissionAuthority) {
    Set<String> normalizedAuthorities = normalizeCodes(permissionAuthority);
    if (normalizedAuthorities.isEmpty()) {
      return;
    }
    String requiredFeatureCode = requireCode(featureCode, "功能编码不能为空");
    featurePermissionRelevanceRepository.unauthorized(requiredFeatureCode, normalizedAuthorities);
    refreshTenantsByFeatureCodes(Set.of(requiredFeatureCode));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Feature> Feature modifyById(S entity) {
    Feature current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("功能不存在"));
    String oldCode = current.getCode();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(Set.of(oldCode));
    Feature updated = (Feature) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      applicationFeatureRelevanceRepository.updateFeatureCode(oldCode, updated.getCode());
      featurePermissionRelevanceRepository.updateFeatureCode(oldCode, updated.getCode());
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

    Set<String> featureCodes = findAllByIds(ids).stream()
        .map(Feature::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (featureCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(featureCodes);
    featurePermissionRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
    applicationFeatureRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
    super.removeByIds(ids);
    refreshPermissionVersion(affectedTenantIds);
  }

  private void refreshTenantsByFeatureCodes(Collection<String> featureCodes) {
    refreshPermissionVersion(tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(featureCodes));
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
