package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.time.Instant;
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
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  private final PermissionVersionRefreshService permissionVersionRefreshService;
  private final PermissionChangeLogRemoteService permissionChangeLogRemoteService;

  /**
   * Feature Service Impl.
   */
  public FeatureServiceImpl(
      FeatureRepository repository,
      DetailsProviderService detailsProviderService,
      FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository,
      ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantRepository tenantRepository,
      PermissionVersionRefreshService permissionVersionRefreshService,
      PermissionChangeLogRemoteService permissionChangeLogRemoteService
  ) {
    super(repository, detailsProviderService);
    this.featurePermissionRelevanceRepository = featurePermissionRelevanceRepository;
    this.applicationFeatureRelevanceRepository = applicationFeatureRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.permissionVersionRefreshService = permissionVersionRefreshService;
    this.permissionChangeLogRemoteService = permissionChangeLogRemoteService;
  }

  @Override
  public <S extends Feature> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return super.limit(attributes, pageable);
    }
    Set<String> featureCodes = tenantPackageRelevanceRepository.findFeatureCodesByTenantId(resolveTenantScope());
    if (featureCodes.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("code", "in:" + String.join(",", featureCodes));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<Feature> findById(String id) {
    Optional<Feature> feature = super.findById(id);
    if (feature.isEmpty() || AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return feature;
    }
    Set<String> featureCodes = tenantPackageRelevanceRepository.findFeatureCodesByTenantId(resolveTenantScope());
    return featureCodes.contains(feature.get().getCode()) ? feature : Optional.empty();
  }

  @Override
  public Collection<String> authorizedPermissions(String featureCode) {
    AuthorizationContext context = getAuthorizationContext();
    if (!hasGlobalFeatureAccess(context)
        && !tenantPackageRelevanceRepository.findFeatureCodesByTenantId(resolveTenantScope()).contains(featureCode)) {
      throw new IllegalArgumentException("功能不存在或不属于当前租户");
    }
    return featurePermissionRelevanceRepository.authorized(requireCode(featureCode, "功能编码不能为空"));
  }

  @Override
  public Collection<String> findAllRequireOrgTenantCodes() {
    return getRepository().findCodesByRequireOrgTenant();
  }

  @Override
  public Collection<Feature> findAllByCodes(Collection<String> featureCodes) {
    Set<String> normalizedCodes = normalizeCodes(featureCodes);
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    if (!hasGlobalFeatureAccess(getAuthorizationContext())) {
      Set<String> visibleFeatureCodes = tenantPackageRelevanceRepository.findFeatureCodesByTenantId(resolveTenantScope());
      normalizedCodes.retainAll(visibleFeatureCodes);
      if (normalizedCodes.isEmpty()) {
        return List.of();
      }
    }
    return getRepository().findAllByCodes(normalizedCodes);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<FeaturePermissionRelevance> authorizePermissions(FeaturePermissionsRelevanceDto dto) {
    requirePlatformAdministrator();
    Collection<FeaturePermissionRelevance> saved = savePermissionRelations(dto);
    recordPermissionChange("AUTHORIZE", requireCode(dto.getFeatureCode(), "功能编码不能为空"),
        normalizeCodes(dto.getPermissionAuthority()));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<FeaturePermissionRelevance> initializePermissions(FeaturePermissionsRelevanceDto dto) {
    return savePermissionRelations(dto);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Feature initializeFeature(Feature feature) {
    return (Feature) super.modifyById(feature);
  }

  private Collection<FeaturePermissionRelevance> savePermissionRelations(FeaturePermissionsRelevanceDto dto) {
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
    permissionVersionRefreshService.refreshByFeatureCodes(Set.of(featureCode));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedPermissions(String featureCode, Set<String> permissionAuthority) {
    requirePlatformAdministrator();
    Set<String> normalizedAuthorities = normalizeCodes(permissionAuthority);
    if (normalizedAuthorities.isEmpty()) {
      return;
    }
    String requiredFeatureCode = requireCode(featureCode, "功能编码不能为空");
    featurePermissionRelevanceRepository.unauthorized(requiredFeatureCode, normalizedAuthorities);
    permissionVersionRefreshService.refreshByFeatureCodes(Set.of(requiredFeatureCode));
    recordPermissionChange("UNAUTHORIZE", requiredFeatureCode, normalizedAuthorities);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Feature> Feature modifyById(S entity) {
    requirePlatformAdministrator();
    Feature current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("功能不存在"));
    String oldCode = current.getCode();
    Boolean oldPublicAccess = current.getPublicAccess();
    Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(Set.of(oldCode));
    Feature updated = (Feature) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      applicationFeatureRelevanceRepository.updateFeatureCode(oldCode, updated.getCode());
      featurePermissionRelevanceRepository.updateFeatureCode(oldCode, updated.getCode());
      permissionVersionRefreshService.refreshTenants(affectedTenantIds);
    } else if (!Objects.equals(oldPublicAccess, updated.getPublicAccess())) {
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

    Set<String> featureCodes = findAllByIds(ids).stream()
        .map(Feature::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (featureCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    final Set<String> affectedTenantIds = tenantPackageRelevanceRepository.findTenantIdsByFeatureCodes(featureCodes);
    featurePermissionRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
    applicationFeatureRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
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

  private void recordPermissionChange(String action, String featureCode, Set<String> permissionAuthorities) {
    AuthorizationContext authorizationContext = getAuthorizationContext();
    if (authorizationContext == null || authorizationContext.getUserId() == null || authorizationContext.getUserId().isBlank()) {
      return;
    }

    Set<String> normalizedAuthorities = permissionAuthorities == null ? Set.of() : permissionAuthorities;
    PermissionChangeLogRecordCommand command = new PermissionChangeLogRecordCommand();
    command.setChangedAt(Instant.now());
    command.setChangeType("FEATURE_PERMISSION");
    command.setAction(action);
    command.setSubjectType("FEATURE");
    command.setSubjectId(featureCode);
    command.setSubjectLabel(resolveFeatureLabel(featureCode));
    command.setTargetType("PERMISSION");
    command.setTargetSummary(joinValues(normalizedAuthorities));
    command.setTargetCount(normalizedAuthorities.size());
    command.setOperatorId(authorizationContext.getUserId());
    command.setTenantId(resolveTenantScope());
    command.setContextId(authorizationContext.getContextId());
    command.setSourceService("common");
    command.setDescription(action + " FEATURE_PERMISSION [" + command.getSubjectLabel() + "] -> [" + command.getTargetSummary() + "]");
    permissionChangeLogRemoteService.record(command);
  }

  private String resolveFeatureLabel(String featureCode) {
    return findAllByCodes(Set.of(featureCode)).stream()
        .findFirst()
        .map(feature -> firstNonBlank(feature.getCode(), feature.getName()))
        .orElse(featureCode);
  }

  private String resolveTenantScope() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    var ctx = getAuthorizationContext();
    String userId = ctx != null ? ctx.getUserId() : null;
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return tenantRepository.findPersonalTenantByOwnerId(userId)
        .map(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant::getId)
        .orElse(null);
  }

  private String joinValues(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .collect(Collectors.joining(","));
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private void requirePlatformAdministrator() {
    AuthorizationScopeGuards.requirePlatformAdministrator(getAuthorizationContext());
  }

  private boolean hasGlobalFeatureAccess(AuthorizationContext context) {
    return context == null || AuthorizationScopeGuards.isPlatformAdministrator(context);
  }
}
