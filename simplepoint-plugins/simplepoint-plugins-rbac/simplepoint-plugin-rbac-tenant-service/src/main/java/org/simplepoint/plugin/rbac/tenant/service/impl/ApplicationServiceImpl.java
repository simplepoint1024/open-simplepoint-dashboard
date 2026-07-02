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
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ApplicationService;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  private final PermissionVersionRefreshService permissionVersionRefreshService;

  /**
   * Application Service Impl.
   */
  public ApplicationServiceImpl(
      ApplicationRepository repository,
      DetailsProviderService detailsProviderService,
      ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository,
      PermissionVersionRefreshService permissionVersionRefreshService
  ) {
    super(repository, detailsProviderService);
    this.applicationFeatureRelevanceRepository = applicationFeatureRelevanceRepository;
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.permissionVersionRefreshService = permissionVersionRefreshService;
  }

  @Override
  public <S extends Application> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return super.limit(attributes, pageable);
    }
    Set<String> applicationCodes = tenantPackageRelevanceRepository.findApplicationCodesByTenantId(resolveTenantScope());
    if (applicationCodes.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("code", "in:" + String.join(",", applicationCodes));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<Application> findById(String id) {
    Optional<Application> application = super.findById(id);
    if (application.isEmpty() || AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return application;
    }
    Set<String> applicationCodes = tenantPackageRelevanceRepository.findApplicationCodesByTenantId(resolveTenantScope());
    return applicationCodes.contains(application.get().getCode()) ? application : Optional.empty();
  }

  @Override
  public Collection<String> authorizedFeatures(String applicationCode) {
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())
        && !tenantPackageRelevanceRepository.findApplicationCodesByTenantId(resolveTenantScope()).contains(applicationCode)) {
      throw new IllegalArgumentException("应用不存在或不属于当前租户");
    }
    return applicationFeatureRelevanceRepository.authorized(requireCode(applicationCode, "应用编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<ApplicationFeatureRelevance> authorizeFeatures(ApplicationFeaturesRelevanceDto dto) {
    requirePlatformAdministrator();
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
    permissionVersionRefreshService.refreshByApplicationCodes(Set.of(applicationCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedFeatures(String applicationCode, Set<String> featureCodes) {
    requirePlatformAdministrator();
    Set<String> normalizedFeatureCodes = normalizeCodes(featureCodes);
    if (normalizedFeatureCodes.isEmpty()) {
      return;
    }
    String requiredApplicationCode = requireCode(applicationCode, "应用编码不能为空");
    applicationFeatureRelevanceRepository.unauthorized(requiredApplicationCode, normalizedFeatureCodes);
    permissionVersionRefreshService.refreshByApplicationCodes(Set.of(requiredApplicationCode));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Application> Application modifyById(S entity) {
    requirePlatformAdministrator();
    Application current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("应用不存在"));
    String oldCode = current.getCode();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(Set.of(oldCode));
    Application updated = (Application) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
      applicationFeatureRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
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

    Set<String> applicationCodes = findAllByIds(ids).stream()
        .map(Application::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (applicationCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    final Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByApplicationCodes(applicationCodes);
    applicationFeatureRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
    packageApplicationRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
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
