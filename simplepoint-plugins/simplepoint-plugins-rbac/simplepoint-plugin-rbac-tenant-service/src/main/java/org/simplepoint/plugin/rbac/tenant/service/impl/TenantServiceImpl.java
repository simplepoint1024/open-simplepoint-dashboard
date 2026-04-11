package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tenant service implementation.
 */
@AmqpRemoteService
public class TenantServiceImpl extends BaseServiceImpl<TenantRepository, Tenant, String> implements TenantService {

    private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
    private final TenantUserRelevanceRepository tenantUserRelevanceRepository;

    public TenantServiceImpl(
            TenantRepository repository,
            DetailsProviderService detailsProviderService,
            TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
            TenantUserRelevanceRepository tenantUserRelevanceRepository
    ) {
        super(repository, detailsProviderService);
        this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
        this.tenantUserRelevanceRepository = tenantUserRelevanceRepository;
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
        Long permissionVersion = 0L;
        if (!"default".equals(resolvedTenantId)) {
            permissionVersion = getRepository().getTenantPermissionVersion(resolvedTenantId);
        }
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
    public Page<UserRelevanceVo> ownerItems(Pageable pageable) {
        return tenantUserRelevanceRepository.items(pageable);
    }

    @Override
    public Page<UserRelevanceVo> userItems(String tenantId, Pageable pageable) {
        requireTenantMemberManager(tenantId);
        return tenantUserRelevanceRepository.items(pageable);
    }

    @Override
    public Collection<String> authorizedUsers(String tenantId) {
        Tenant tenant = requireTenantMemberManager(tenantId);
        LinkedHashSet<String> userIds = new LinkedHashSet<>(tenantUserRelevanceRepository.authorized(tenant.getId()));
        if (tenant.getOwnerId() != null && !tenant.getOwnerId().isBlank()) {
            userIds.add(tenant.getOwnerId());
        }
        return userIds;
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
    @Transactional(rollbackFor = Exception.class)
    public Collection<TenantUserRelevance> authorizeUsers(TenantUsersRelevanceDto dto) {
        Tenant tenant = requireTenantMemberManager(dto.getTenantId());
        Set<String> userIds = normalizeCodes(dto.getUserIds());
        if (userIds.isEmpty()) {
            return List.of();
        }
        validateUsersExist(userIds);

        LinkedHashSet<String> existingUserIds = new LinkedHashSet<>(tenantUserRelevanceRepository.authorized(tenant.getId()));
        LinkedHashSet<TenantUserRelevance> relations = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (!existingUserIds.add(userId)) {
                continue;
            }
            TenantUserRelevance relevance = new TenantUserRelevance();
            relevance.setTenantId(tenant.getId());
            relevance.setUserId(userId);
            relations.add(relevance);
        }

        if (relations.isEmpty()) {
            return List.of();
        }

        Collection<TenantUserRelevance> saved = tenantUserRelevanceRepository.saveAll(relations);
        refreshPermissionVersion(Set.of(tenant.getId()));
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unauthorizedUsers(String tenantId, Set<String> userIds) {
        Tenant tenant = requireTenantMemberManager(tenantId);
        Set<String> normalizedUserIds = normalizeCodes(userIds);
        if (normalizedUserIds.isEmpty()) {
            return;
        }
        if (tenant.getOwnerId() != null && normalizedUserIds.contains(tenant.getOwnerId())) {
            throw new IllegalArgumentException("租户所有者不能移出租户成员");
        }
        tenantUserRelevanceRepository.unauthorized(tenant.getId(), normalizedUserIds);
        refreshPermissionVersion(Set.of(tenant.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <S extends Tenant> S create(S entity) {
        Authentication authentication = getRequiredAuthentication();
        if (entity.getPermissionVersion() == null) {
            entity.setPermissionVersion(0L);
        }
        if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
            entity.setOwnerId(authentication.getName());
        }
        validateUsersExist(Set.of(entity.getOwnerId()));
        S created = super.create(entity);
        ensureOwnerMembership(created.getId(), created.getOwnerId());
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <S extends Tenant> Tenant modifyById(S entity) {
        Tenant current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        if (entity.getPermissionVersion() == null) {
            entity.setPermissionVersion(current.getPermissionVersion());
        }
        if (entity.getOwnerId() == null || entity.getOwnerId().isBlank()) {
            entity.setOwnerId(current.getOwnerId());
        }
        validateUsersExist(Set.of(entity.getOwnerId()));
        boolean ownerChanged = !Objects.equals(current.getOwnerId(), entity.getOwnerId());
        Tenant modified = super.modifyById(entity);
        boolean ownerMembershipAdded = ensureOwnerMembership(modified.getId(), modified.getOwnerId());
        if (ownerChanged || ownerMembershipAdded) {
            refreshPermissionVersion(Set.of(modified.getId()));
        }
        return modified;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        tenantPackageRelevanceRepository.deleteAllByTenantIds(ids);
        tenantUserRelevanceRepository.deleteAllByTenantIds(ids);
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

    private Tenant requireTenantMemberManager(String tenantId) {
        String requiredTenantId = requireTenantId(tenantId);
        Tenant tenant = findById(requiredTenantId).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        Authentication authentication = getRequiredAuthentication();
        if (isAdministrator(authentication) || Objects.equals(authentication.getName(), tenant.getOwnerId())) {
            return tenant;
        }
        throw new AccessDeniedException("仅管理员或租户所有者可以配置租户成员");
    }

    private boolean ensureOwnerMembership(String tenantId, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return false;
        }
        Set<String> existingUserIds = new LinkedHashSet<>(tenantUserRelevanceRepository.authorized(tenantId));
        if (existingUserIds.contains(ownerId)) {
            return false;
        }
        TenantUserRelevance relevance = new TenantUserRelevance();
        relevance.setTenantId(tenantId);
        relevance.setUserId(ownerId);
        tenantUserRelevanceRepository.saveAll(List.of(relevance));
        return true;
    }

    private void validateUsersExist(Set<String> userIds) {
        Set<String> normalizedUserIds = normalizeCodes(userIds);
        if (normalizedUserIds.isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        Set<String> existingUserIds = tenantUserRelevanceRepository.existingUserIds(normalizedUserIds);
        if (existingUserIds.size() == normalizedUserIds.size()) {
            return;
        }
        LinkedHashSet<String> missingUserIds = new LinkedHashSet<>(normalizedUserIds);
        missingUserIds.removeAll(existingUserIds);
        throw new IllegalArgumentException("用户不存在: " + String.join(",", missingUserIds));
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

    private static boolean isAdministrator(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_Administrator".equals(authority.getAuthority()));
    }
}
