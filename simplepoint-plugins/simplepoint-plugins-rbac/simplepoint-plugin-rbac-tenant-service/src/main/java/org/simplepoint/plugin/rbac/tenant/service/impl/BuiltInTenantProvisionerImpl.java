package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.ADMIN_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.AI_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.CORE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.MEMBER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.OWNER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.PERSONAL_PACKAGE;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.STORAGE_APPLICATION;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository-level provisioner used by bootstrap and resource-catalog synchronization.
 */
@Service
public class BuiltInTenantProvisionerImpl implements BuiltInTenantProvisioner {

  private static final Set<String> MEMBER_STARTER_RESOURCES = Set.of(
      "dashboard.view",
      "storage.objects.view",
      "ai.knowledge-bases.view"
  );

  private final ApplicationRepository applicationRepository;
  private final PackageRepository packageRepository;
  private final TenantRepository tenantRepository;
  private final ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final RoleRepository roleRepository;
  private final RoleResourceGrantRepository roleResourceGrantRepository;
  private final BuiltInTenantBootstrapProperties properties;

  /**
   * Creates the provisioner.
   */
  public BuiltInTenantProvisionerImpl(
      ApplicationRepository applicationRepository,
      PackageRepository packageRepository,
      TenantRepository tenantRepository,
      ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      RoleRepository roleRepository,
      RoleResourceGrantRepository roleResourceGrantRepository,
      BuiltInTenantBootstrapProperties properties
  ) {
    this.applicationRepository = applicationRepository;
    this.packageRepository = packageRepository;
    this.tenantRepository = tenantRepository;
    this.applicationResourceRelevanceRepository = applicationResourceRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.roleRepository = roleRepository;
    this.roleResourceGrantRepository = roleResourceGrantRepository;
    this.properties = properties;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void provisionApplicationResources(
      String resourceOwner,
      Collection<String> tenantResourceCodes,
      Collection<String> personalResourceCodes
  ) {
    String owner = normalizeOwner(resourceOwner);
    if (owner == null) {
      return;
    }
    Set<String> tenantCodes = normalizeCodes(tenantResourceCodes);
    Set<String> personalCodes = normalizeCodes(personalResourceCodes);
    if ("common".equals(owner)) {
      provisionApplicationResources(
          STORAGE_APPLICATION,
          selectByPrefix(tenantCodes, "storage."),
          selectByPrefix(personalCodes, "storage.")
      );
      provisionApplicationResources(
          CORE_APPLICATION,
          excludePrefix(tenantCodes, "storage."),
          excludePrefix(personalCodes, "storage.")
      );
      return;
    }
    if ("ai".equals(owner)) {
      provisionApplicationResources(AI_APPLICATION, tenantCodes, personalCodes);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void provisionPersonalTenant(String tenantId) {
    if (!hasText(tenantId) || !existsPackage(PERSONAL_PACKAGE)) {
      return;
    }
    Set<String> current = normalizeCodes(tenantPackageRelevanceRepository.authorized(tenantId));
    if (current.contains(PERSONAL_PACKAGE)) {
      return;
    }
    TenantPackageRelevance relevance = new TenantPackageRelevance();
    relevance.setTenantId(tenantId);
    relevance.setPackageCode(PERSONAL_PACKAGE);
    tenantPackageRelevanceRepository.saveAll(List.of(relevance));
  }

  private void provisionApplicationResources(
      String applicationCode,
      Set<String> tenantCodes,
      Set<String> personalCodes
  ) {
    if (!existsApplication(applicationCode)) {
      return;
    }
    Set<String> allCodes = new LinkedHashSet<>(tenantCodes);
    allCodes.addAll(personalCodes);
    Set<String> existing = normalizeCodes(applicationResourceRelevanceRepository.authorized(applicationCode));
    allCodes.removeAll(existing);
    if (!allCodes.isEmpty()) {
      List<ApplicationResourceRelevance> relations = allCodes.stream().map(code -> {
        ApplicationResourceRelevance relevance = new ApplicationResourceRelevance();
        relevance.setApplicationCode(applicationCode);
        relevance.setResourceCode(code);
        return relevance;
      }).toList();
      applicationResourceRelevanceRepository.saveAll(relations);
    }
    provisionBuiltInRoleResources(tenantCodes);
  }

  private void provisionBuiltInRoleResources(Set<String> tenantCodes) {
    if (tenantCodes.isEmpty()) {
      return;
    }
    String tenantId = tenantRepository.findAll(Map.of("name", properties.getOrganizationName())).stream()
        .map(tenant -> tenant.getId())
        .filter(BuiltInTenantProvisionerImpl::hasText)
        .findFirst()
        .orElse(null);
    if (tenantId == null) {
      return;
    }
    provisionRoleResources(tenantId, OWNER_ROLE_AUTHORITY, tenantCodes);
    provisionRoleResources(tenantId, ADMIN_ROLE_AUTHORITY, tenantCodes);
    Set<String> memberCodes = tenantCodes.stream()
        .filter(MEMBER_STARTER_RESOURCES::contains)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    provisionRoleResources(tenantId, MEMBER_ROLE_AUTHORITY, memberCodes);
  }

  private void provisionRoleResources(String tenantId, String authority, Set<String> resourceCodes) {
    if (resourceCodes.isEmpty()) {
      return;
    }
    Role role = roleRepository.findFirstByTenantIdAndAuthority(tenantId, authority)
        .orElse(null);
    if (role == null || !hasText(role.getId())) {
      return;
    }
    List<RoleResourceGrant> currentGrants = roleResourceGrantRepository.findByRoleIdIn(List.of(role.getId()));
    Set<String> currentCodes = currentGrants.stream()
        .map(RoleResourceGrant::getResourceCode)
        .filter(BuiltInTenantProvisionerImpl::hasText)
        .collect(Collectors.toSet());
    RoleResourceGrant scopeTemplate = currentGrants.stream().findFirst().orElse(null);
    List<RoleResourceGrant> missing = resourceCodes.stream()
        .filter(code -> !currentCodes.contains(code))
        .map(code -> newRoleGrant(tenantId, role.getId(), code, scopeTemplate))
        .toList();
    if (!missing.isEmpty()) {
      roleResourceGrantRepository.saveAll(missing);
    }
  }

  private RoleResourceGrant newRoleGrant(
      String tenantId,
      String roleId,
      String resourceCode,
      RoleResourceGrant scopeTemplate
  ) {
    RoleResourceGrant grant = new RoleResourceGrant();
    grant.setTenantId(tenantId);
    grant.setRoleId(roleId);
    grant.setResourceCode(resourceCode);
    if (scopeTemplate != null) {
      grant.setDataScopeId(scopeTemplate.getDataScopeId());
      grant.setFieldScopeId(scopeTemplate.getFieldScopeId());
    }
    return grant;
  }

  private boolean existsApplication(String code) {
    return !applicationRepository.findAll(Map.of("code", code)).isEmpty();
  }

  private boolean existsPackage(String code) {
    return !packageRepository.findAll(Map.of("code", code)).isEmpty();
  }

  private static Set<String> selectByPrefix(Set<String> codes, String prefix) {
    return codes.stream()
        .filter(code -> code.startsWith(prefix))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> excludePrefix(Set<String> codes, String prefix) {
    return codes.stream()
        .filter(code -> !code.startsWith(prefix))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return codes.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(BuiltInTenantProvisionerImpl::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String normalizeOwner(String owner) {
    return hasText(owner) ? owner.trim().toLowerCase(Locale.ROOT) : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
