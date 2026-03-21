package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant service implementation.
 */
@Service
public class TenantServiceImpl extends BaseServiceImpl<TenantRepository, Tenant, String> implements TenantService {

  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;

  public TenantServiceImpl(
      TenantRepository repository,
      DetailsProviderService detailsProviderService,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository
  ) {
    super(repository, detailsProviderService);
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
  }

  @Override
  public Set<NamedTenantVo> getTenantsByUserId(String userId) {
    return getRepository().getTenantsByUserId(userId);
  }

  @Override
  public Set<NamedTenantVo> getCurrentUserTenants() {
    return this.getTenantsByUserId(getRequiredAuthentication().getName());
  }

  @Override
  public String calculatePermissionContextId(String tenantId) {
    Authentication authentication = getRequiredAuthentication();
    String resolvedTenantId = resolveTenantId(tenantId, authentication.getName());
    Long permissionVersion = "default".equals(resolvedTenantId)
        ? 0L
        : getRepository().getTenantPermissionVersion(resolvedTenantId);
    if (permissionVersion == null) {
      permissionVersion = 0L;
    }
    return sha256(resolvedTenantId + ":" + authentication.getName() + ":" + permissionVersion);
  }

  @Override
  public Collection<String> authorizedPackages(String tenantId) {
    return tenantPackageRelevanceRepository.authorized(requireTenantId(tenantId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<TenantPackageRelevance> authorizePackages(TenantPackagesRelevanceDto dto) {
    String tenantId = requireTenantId(dto.getTenantId());
    Set<String> packageCodes = normalizeCodes(dto.getPackageCodes());
    if (packageCodes.isEmpty()) {
      return List.of();
    }

    Set<TenantPackageRelevance> relations = new LinkedHashSet<>(packageCodes.size());
    for (String packageCode : packageCodes) {
      TenantPackageRelevance relevance = new TenantPackageRelevance();
      relevance.setTenantId(tenantId);
      relevance.setPackageCode(packageCode);
      relations.add(relevance);
    }

    Collection<TenantPackageRelevance> saved = tenantPackageRelevanceRepository.saveAll(relations);
    refreshPermissionVersion(Set.of(tenantId));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorizedPackages(String tenantId, Set<String> packageCodes) {
    Set<String> normalizedPackageCodes = normalizeCodes(packageCodes);
    if (normalizedPackageCodes.isEmpty()) {
      return;
    }
    String requiredTenantId = requireTenantId(tenantId);
    tenantPackageRelevanceRepository.unauthorized(requiredTenantId, normalizedPackageCodes);
    refreshPermissionVersion(Set.of(requiredTenantId));
  }

  @Override
  public <S extends Tenant> S create(S entity) {
    Authentication authentication = getRequiredAuthentication();
    if (entity.getPermissionVersion() == null) {
      entity.setPermissionVersion(0L);
    }
    if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
      entity.setOwnerId(authentication.getName());
    }
    return super.create(entity);
  }

  @Override
  public <S extends Tenant> Tenant modifyById(S entity) {
    Tenant current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
    if (entity.getPermissionVersion() == null) {
      entity.setPermissionVersion(current.getPermissionVersion());
    }
    if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
      entity.setOwnerId(current.getOwnerId());
    }
    return super.modifyById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    tenantPackageRelevanceRepository.deleteAllByTenantIds(ids);
    super.removeByIds(ids);
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Authentication getRequiredAuthentication() {
    SecurityContext context = SecurityContextHolder.getContext();
    Authentication authentication = context == null ? null : context.getAuthentication();
    if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
      throw new IllegalStateException("当前未认证用户");
    }
    return authentication;
  }

  private void refreshPermissionVersion(Collection<String> tenantIds) {
    if (tenantIds == null || tenantIds.isEmpty()) {
      return;
    }
    getRepository().increasePermissionVersion(tenantIds);
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Set.of();
    }
    return codes.stream()
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
  }

  private static String requireTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("租户ID不能为空");
    }
    return tenantId;
  }

  private String resolveTenantId(String tenantId, String userId) {
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }

    return getTenantsByUserId(userId).stream()
        .sorted(
            Comparator.comparing(NamedTenantVo::tenantName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NamedTenantVo::tenantId, Comparator.nullsLast(Comparator.naturalOrder()))
        )
        .map(NamedTenantVo::tenantId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse("default");
  }
}
