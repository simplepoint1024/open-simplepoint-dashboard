package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.AI_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.APP_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.CORE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.STORAGE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.WORKFLOW_APPLICATION;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains built-in application membership; catalog registration never creates tenant role grants.
 */
@Service
public class BuiltInTenantProvisionerImpl implements BuiltInTenantProvisioner {

  private final ApplicationRepository applicationRepository;
  private final ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository;
  private final BuiltInTenantResourcePreparation preparation;

  /** Creates the provisioner. */
  public BuiltInTenantProvisionerImpl(
      ApplicationRepository applicationRepository,
      ApplicationResourceRelevanceRepository applicationResourceRelevanceRepository,
      BuiltInTenantResourcePreparation preparation) {
    this.applicationRepository = applicationRepository;
    this.applicationResourceRelevanceRepository = applicationResourceRelevanceRepository;
    this.preparation = preparation;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void provisionApplicationResources(
      String resourceOwner,
      Collection<String> tenantResourceCodes,
      Collection<String> personalResourceCodes) {
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
          selectByPrefix(personalCodes, "storage."));
      provisionApplicationResources(
          CORE_APPLICATION,
          excludePrefix(tenantCodes, "storage."),
          excludePrefix(personalCodes, "storage."));
      // Even an empty authoritative declaration must validate pending readiness instead of silently
      // skipping it.
      preparation.completePending();
      return;
    }
    if ("ai".equals(owner)) {
      provisionApplicationResources(AI_APPLICATION, tenantCodes, personalCodes);
      return;
    }
    if ("app".equals(owner)) {
      provisionApplicationResources(APP_APPLICATION, tenantCodes, personalCodes);
      return;
    }
    if ("workflow".equals(owner)) {
      provisionApplicationResources(WORKFLOW_APPLICATION, tenantCodes, personalCodes);
      return;
    }
    if ("dna".equals(owner)) {
      provisionApplicationResources(
          APP_APPLICATION, selectByPrefix(tenantCodes, "dna.runtime."), Set.of());
    }
  }

  private void provisionApplicationResources(
      String applicationCode, Set<String> tenantCodes, Set<String> personalCodes) {
    if (!existsApplication(applicationCode)) {
      return;
    }
    Set<String> allCodes = new LinkedHashSet<>(tenantCodes);
    allCodes.addAll(personalCodes);
    Set<String> existing =
        normalizeCodes(applicationResourceRelevanceRepository.authorized(applicationCode));
    allCodes.removeAll(existing);
    if (!allCodes.isEmpty()) {
      List<ApplicationResourceRelevance> relations =
          allCodes.stream()
              .map(
                  code -> {
                    ApplicationResourceRelevance relevance = new ApplicationResourceRelevance();
                    relevance.setApplicationCode(applicationCode);
                    relevance.setResourceCode(code);
                    return relevance;
                  })
              .toList();
      applicationResourceRelevanceRepository.saveAll(relations);
    }
  }

  private boolean existsApplication(String code) {
    return !applicationRepository.findAll(Map.of("code", code)).isEmpty();
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
