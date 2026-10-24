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
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationResourcesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ApplicationService;
import org.simplepoint.security.service.ResourceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service implementation. */
@Service
public class ApplicationServiceImpl
    extends BaseServiceImpl<ApplicationRepository, Application, String>
    implements ApplicationService {

  private final ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository;
  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;
  private final ResourceService resourceService;

  /** Application Service Impl. */
  public ApplicationServiceImpl(
      ApplicationRepository repository,
      DetailsProviderService detailsProviderService,
      ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      ResourceService resourceService) {
    super(repository, detailsProviderService);
    this.applicationResourceRelevanceRepository = applicationResourceRelevanceRepository;
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.resourceService = resourceService;
  }

  @Override
  public Map<String, Object> schema() {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.schema();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Application> S create(S entity) {
    requirePlatformAdministrator();
    return super.create(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Application> create(Collection<Application> entities) {
    requirePlatformAdministrator();
    return super.create(entities);
  }

  @Override
  public List<Application> findAllByIds(Iterable<String> ids) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.findAllByIds(ids);
  }

  @Override
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(Class<Application> domainClass) {
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
  public <S extends Application> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.limit(attributes, pageable);
  }

  @Override
  public Page<Application> assignmentItems(
      String parent, boolean selected, Map<String, String> filters, Pageable pageable) {
    String packageCode = requireCode(parent, "套餐编码不能为空");
    var memberships = new java.util.ArrayList<org.simplepoint.api.base.QueryMembership>();
    PlatformTemplateBoundary.read(getAuthorizationContext());

    memberships.add(
        new org.simplepoint.api.base.QueryMembership(
            "code",
            packageApplicationRelevanceRepository.authorized(packageCode).stream().toList(),
            selected));
    return super.limitMemberships(filters, memberships, pageable);
  }

  @Override
  public Optional<Application> findById(String id) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return super.findById(id);
  }

  @Override
  public Collection<String> authorizedResources(String applicationCode) {
    PlatformTemplateBoundary.read(getAuthorizationContext());
    return applicationResourceRelevanceRepository.authorized(
        requireCode(applicationCode, "应用编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<ApplicationResourceRelevance> authorizeResources(
      ApplicationResourcesRelevanceDto dto) {
    requirePlatformAdministrator();
    if (dto == null) {
      throw new IllegalArgumentException("应用资源授权参数不能为空");
    }
    final String applicationCode = requireCode(dto.getApplicationCode(), "应用编码不能为空");
    Set<String> resourceCodes = normalizeCodes(dto.getResourceCodes());
    if (resourceCodes.isEmpty()) {
      return List.of();
    }
    Set<String> deliverableCodes =
        new LinkedHashSet<>(resourceService.filterDistributableCodes(resourceCodes));
    if (!deliverableCodes.containsAll(resourceCodes)) {
      Set<String> rejected = new LinkedHashSet<>(resourceCodes);
      rejected.removeAll(deliverableCodes);
      throw new IllegalArgumentException("应用只能关联租户级或用户级资源: " + String.join(",", rejected));
    }

    Set<ApplicationResourceRelevance> relations = new LinkedHashSet<>(resourceCodes.size());
    for (String resourceCode : resourceCodes) {
      ApplicationResourceRelevance relevance = new ApplicationResourceRelevance();
      relevance.setApplicationCode(applicationCode);
      relevance.setResourceCode(resourceCode);
      relations.add(relevance);
    }

    Collection<ApplicationResourceRelevance> saved =
        applicationResourceRelevanceRepository.saveAll(relations);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedResources(String applicationCode, Set<String> resourceCodes) {
    requirePlatformAdministrator();
    Set<String> normalizedResourceCodes = normalizeCodes(resourceCodes);
    if (normalizedResourceCodes.isEmpty()) {
      return;
    }
    String requiredApplicationCode = requireCode(applicationCode, "应用编码不能为空");
    applicationResourceRelevanceRepository.unauthorized(
        requiredApplicationCode, normalizedResourceCodes);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Application> Application modifyById(S entity) {
    requirePlatformAdministrator();
    Application current =
        findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("应用不存在"));
    String oldCode = current.getCode();
    Application updated = (Application) super.modifyById(entity);
    if (!Objects.equals(oldCode, updated.getCode())) {
      packageApplicationRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
      applicationResourceRelevanceRepository.updateApplicationCode(oldCode, updated.getCode());
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

    Set<String> applicationCodes =
        findAllByIds(ids).stream()
            .map(Application::getCode)
            .filter(code -> code != null && !code.isBlank())
            .collect(Collectors.toSet());
    if (applicationCodes.isEmpty()) {
      super.removeByIds(ids);
      return;
    }

    applicationResourceRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
    packageApplicationRelevanceRepository.deleteAllByApplicationCodes(applicationCodes);
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
