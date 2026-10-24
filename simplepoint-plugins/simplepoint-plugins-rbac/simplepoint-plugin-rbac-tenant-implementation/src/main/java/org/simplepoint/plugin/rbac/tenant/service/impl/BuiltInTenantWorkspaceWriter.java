package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.ADMIN_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.AI_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.APP_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.CORE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.MEMBER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.ORGANIZATION_PACKAGE;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.OWNER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.PERSONAL_PACKAGE;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.STORAGE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.WORKFLOW_APPLICATION;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Creates built-in organization facts atomically, with resource readiness deferred to declaration registration.
 */
@Service
public class BuiltInTenantWorkspaceWriter {

  private final ApplicationRepository applicationRepository;
  private final PackageRepository packageRepository;
  private final TenantRepository tenantRepository;
  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;
  private final TenantUserRelevanceRepository tenantUserRelevanceRepository;
  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final UserRoleRelevanceRepository userRoleRelevanceRepository;
  private final UserManagementService usersService;
  private final BuiltInTenantBootstrapProperties properties;
  private final BuiltInTenantResourcePreparation preparation;

  /**
   * Creates the workspace initializer.
   */
  public BuiltInTenantWorkspaceWriter(
      ApplicationRepository applicationRepository,
      PackageRepository packageRepository,
      TenantRepository tenantRepository,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantUserRelevanceRepository tenantUserRelevanceRepository,
      RoleRepository roleRepository,
      UserRepository userRepository,
      UserRoleRelevanceRepository userRoleRelevanceRepository,
      UserManagementService usersService,
      BuiltInTenantBootstrapProperties properties,
      BuiltInTenantResourcePreparation preparation
  ) {
    this.applicationRepository = applicationRepository;
    this.packageRepository = packageRepository;
    this.tenantRepository = tenantRepository;
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantUserRelevanceRepository = tenantUserRelevanceRepository;
    this.roleRepository = roleRepository;
    this.userRepository = userRepository;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.usersService = usersService;
    this.properties = properties;
    this.preparation = preparation;
  }

  /** Creates seed facts atomically; no web caller can use this path to bypass normal tenant provisioning. */
  @Transactional(rollbackFor = Exception.class)
  public void initializeWorkspace() {
    if (RequestContextHolder.getRequestAttributes() != null
        || AuthorizationContextHolder.getContext() != null
        || SecurityContextHolder.getContext().getAuthentication() != null) {
      throw new AccessDeniedException("Workspace bootstrap is unavailable in request contexts");
    }
    final User owner = requireUser(properties.getOwnerSubject(), "组织所有者");
    final User manager = requireUser(properties.getManagerSubject(), "租户管理员");
    final User member = requireUser(properties.getMemberSubject(), "普通成员");

    ensureApplication(CORE_APPLICATION, "SimplePoint 核心工作台", "账户、角色和通用工作台功能", "/dashboard", 10);
    ensureApplication(
        STORAGE_APPLICATION,
        "SimplePoint 对象存储",
        "统一对象存储和租户文件管理",
        "/workspace/object-storage",
        20
    );
    ensureApplication(AI_APPLICATION, "SimplePoint AI", "模型供应商、模型目录和知识库能力", "/ai", 30);
    ensureApplication(APP_APPLICATION, "SimplePoint 应用工作台", "表单与应用管理", "/app/workbench", 40);
    ensureApplication(WORKFLOW_APPLICATION, "SimplePoint 流程中心", "流程设计、待办和事项追踪", "/workflow/tasks", 50);

    ensurePackage(
        ORGANIZATION_PACKAGE,
        "组织标准版",
        "面向组织租户的默认功能套餐",
        10
    );
    ensurePackage(
        PERSONAL_PACKAGE,
        "个人基础版",
        "面向个人工作空间的默认基础套餐",
        20
    );
    ensurePackageApplications(
        ORGANIZATION_PACKAGE,
        Set.of(CORE_APPLICATION, STORAGE_APPLICATION, AI_APPLICATION, APP_APPLICATION, WORKFLOW_APPLICATION)
    );
    ensurePackageApplications(
        PERSONAL_PACKAGE,
        org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.BASELINE_APPLICATIONS
    );

    Tenant tenant = ensureOrganizationTenant(owner.getId());
    ensureTenantMembers(tenant.getId(), new LinkedHashSet<>(List.of(owner.getId(), manager.getId(), member.getId())));
    Role ownerRole = ensureRole(tenant.getId(), "组织所有者", OWNER_ROLE_AUTHORITY, "拥有组织套餐内全部资源", 100);
    Role managerRole = ensureRole(tenant.getId(), "租户管理员", ADMIN_ROLE_AUTHORITY, "负责成员、角色和业务资源管理", 80);
    Role memberRole = ensureRole(tenant.getId(), "普通成员", MEMBER_ROLE_AUTHORITY, "使用被授予的日常业务功能", 10);
    ensureUserRole(tenant.getId(), owner.getId(), ownerRole.getId());
    ensureUserRole(tenant.getId(), manager.getId(), managerRole.getId());
    ensureUserRole(tenant.getId(), member.getId(), memberRole.getId());
  }

  private User requireUser(String subject, String label) {
    if (!hasText(subject)) {
      throw new IllegalStateException(label + "登录标识未配置");
    }
    User user = usersService.loadUserByPhoneOrEmail(subject.trim());
    if (user == null || !hasText(user.getId())) {
      throw new IllegalStateException(label + "用户不存在: " + subject);
    }
    return user;
  }

  private Application ensureApplication(
      String code,
      String name,
      String description,
      String homepage,
      int sort
  ) {
    Application application = applicationRepository.findAll(Map.of("code", code)).stream()
        .findFirst()
        .orElseGet(Application::new);
    if (hasText(application.getId())) {
      return application;
    }
    application.setCode(code);
    application.setName(name);
    application.setDescription(description);
    application.setHomepage(homepage);
    application.setSort(sort);
    application.setEnabled(Boolean.TRUE);
    return applicationRepository.save(application);
  }

  private Package ensurePackage(String code, String name, String description, int sort) {
    Package item = packageRepository.findAll(Map.of("code", code)).stream()
        .findFirst()
        .orElseGet(Package::new);
    if (hasText(item.getId())) {
      return item;
    }
    item.setCode(code);
    item.setName(name);
    item.setDescription(description);
    item.setPrice(BigDecimal.ZERO);
    item.setDurationDays(null);
    item.setSort(sort);
    item.setEnabled(Boolean.TRUE);
    return packageRepository.save(item);
  }

  private Tenant ensureOrganizationTenant(String ownerId) {
    Tenant tenant = tenantRepository.findAll(Map.of("name", properties.getOrganizationName())).stream()
        .findFirst()
        .orElseGet(Tenant::new);
    if (hasText(tenant.getId())) {
      if (!Objects.equals(ownerId, tenant.getOwnerId()) || tenant.getTenantType() != TenantType.ORGANIZATION) {
        throw new TenantResourceSetupException(
            TenantResourceSetupException.Code.RESOURCE_CONFLICT);
      }
      return tenant;
    }
    tenant.setName(properties.getOrganizationName());
    tenant.setDescription(properties.getOrganizationDescription());
    tenant.setOwnerId(ownerId);
    tenant.setTenantType(TenantType.ORGANIZATION);
    if (tenant.getAuthorizationVersion() == null) {
      tenant.setAuthorizationVersion(0L);
    }
    Tenant created = tenantRepository.save(tenant);
    preparation.stageNewTenant(created.getId());
    return created;
  }

  private void ensurePackageApplications(String packageCode, Set<String> applicationCodes) {
    Set<String> current = normalizeCodes(packageApplicationRelevanceRepository.authorized(packageCode));
    List<PackageApplicationRelevance> missing = applicationCodes.stream()
        .filter(code -> !current.contains(code))
        .map(code -> {
          PackageApplicationRelevance relevance = new PackageApplicationRelevance();
          relevance.setPackageCode(packageCode);
          relevance.setApplicationCode(code);
          return relevance;
        })
        .toList();
    if (!missing.isEmpty()) {
      packageApplicationRelevanceRepository.saveAll(missing);
    }
  }

  private void ensureTenantMembers(String tenantId, Set<String> userIds) {
    Set<String> current = normalizeCodes(tenantUserRelevanceRepository.authorized(tenantId));
    List<TenantUserRelevance> missing = userIds.stream()
        .filter(userId -> !current.contains(userId))
        .map(userId -> {
          TenantUserRelevance relevance = new TenantUserRelevance();
          relevance.setTenantId(tenantId);
          relevance.setUserId(userId);
          return relevance;
        })
        .toList();
    if (!missing.isEmpty()) {
      tenantUserRelevanceRepository.saveAll(missing);
    }
  }

  private Role ensureRole(
      String tenantId,
      String roleName,
      String authority,
      String description,
      int priority
  ) {
    Role role = roleRepository.findFirstByTenantIdAndAuthority(tenantId, authority)
        .orElseGet(Role::new);
    role.setTenantId(tenantId);
    role.setRoleName(roleName);
    role.setAuthority(authority);
    role.setDescription(description);
    role.setPriority(priority);
    return hasText(role.getId()) ? roleRepository.updateById(role) : roleRepository.save(role);
  }

  private void ensureUserRole(String tenantId, String userId, String roleId) {
    Collection<String> currentRoleIds = userRepository.authorized(tenantId, userId);
    if (currentRoleIds != null && currentRoleIds.contains(roleId)) {
      return;
    }
    UserRoleRelevance relevance = new UserRoleRelevance();
    relevance.setTenantId(tenantId);
    relevance.setUserId(userId);
    relevance.setRoleId(roleId);
    userRoleRelevanceRepository.saveAll(List.of(relevance));
  }

  private static Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return codes.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(BuiltInTenantWorkspaceWriter::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
