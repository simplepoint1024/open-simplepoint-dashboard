package org.simplepoint.plugin.rbac.tenant.service.initialize;

import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.ADMIN_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.AI_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.CORE_APPLICATION;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.MEMBER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.ORGANIZATION_PACKAGE;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.OWNER_ROLE_AUTHORITY;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.PERSONAL_PACKAGE;
import static org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.STORAGE_APPLICATION;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.simplepoint.security.service.ResourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Initializes a usable organization workspace with representative packages, applications,
 * members and roles.
 */
@Component
public class BuiltInTenantWorkspaceInitializer {

  private static final String CONTRIBUTION_KEY = "default-tenant-workspace";

  private final ApplicationRepository applicationRepository;
  private final PackageRepository packageRepository;
  private final TenantRepository tenantRepository;
  private final PackageApplicationRelevanceRepository packageApplicationRelevanceRepository;
  private final TenantPackageRelevanceRepository tenantPackageRelevanceRepository;
  private final TenantUserRelevanceRepository tenantUserRelevanceRepository;
  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final UserRoleRelevanceRepository userRoleRelevanceRepository;
  private final UsersService usersService;
  private final ResourceService resourceService;
  private final BuiltInTenantProvisioner provisioner;
  private final BuiltInTenantBootstrapProperties properties;

  /**
   * Creates the workspace initializer.
   */
  public BuiltInTenantWorkspaceInitializer(
      ApplicationRepository applicationRepository,
      PackageRepository packageRepository,
      TenantRepository tenantRepository,
      PackageApplicationRelevanceRepository packageApplicationRelevanceRepository,
      TenantPackageRelevanceRepository tenantPackageRelevanceRepository,
      TenantUserRelevanceRepository tenantUserRelevanceRepository,
      RoleRepository roleRepository,
      UserRepository userRepository,
      UserRoleRelevanceRepository userRoleRelevanceRepository,
      UsersService usersService,
      ResourceService resourceService,
      BuiltInTenantProvisioner provisioner,
      BuiltInTenantBootstrapProperties properties
  ) {
    this.applicationRepository = applicationRepository;
    this.packageRepository = packageRepository;
    this.tenantRepository = tenantRepository;
    this.packageApplicationRelevanceRepository = packageApplicationRelevanceRepository;
    this.tenantPackageRelevanceRepository = tenantPackageRelevanceRepository;
    this.tenantUserRelevanceRepository = tenantUserRelevanceRepository;
    this.roleRepository = roleRepository;
    this.userRepository = userRepository;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.usersService = usersService;
    this.resourceService = resourceService;
    this.provisioner = provisioner;
    this.properties = properties;
  }

  /**
   * Registers the built-in workspace bootstrap contribution.
   */
  @Bean
  public PlatformBootstrapContribution builtInTenantWorkspaceBootstrapContribution() {
    return () -> BootstrapContribution.versioned(
        "rbac-tenant",
        "workspace",
        CONTRIBUTION_KEY,
        "1",
        400,
        this::initializeWorkspace
    );
  }

  private void initializeWorkspace() {
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
        Set.of(CORE_APPLICATION, STORAGE_APPLICATION, AI_APPLICATION)
    );
    ensurePackageApplications(
        PERSONAL_PACKAGE,
        Set.of(CORE_APPLICATION, STORAGE_APPLICATION)
    );

    Tenant tenant = ensureOrganizationTenant(owner.getId());
    ensureTenantMembers(tenant.getId(), Set.of(owner.getId(), manager.getId(), member.getId()));
    ensureTenantPackage(tenant.getId(), ORGANIZATION_PACKAGE);

    provisionPersonalPackage(owner);
    provisionPersonalPackage(manager);
    provisionPersonalPackage(member);

    Role ownerRole = ensureRole(tenant.getId(), "组织所有者", OWNER_ROLE_AUTHORITY, "拥有组织套餐内全部资源", 100);
    Role managerRole = ensureRole(tenant.getId(), "租户管理员", ADMIN_ROLE_AUTHORITY, "负责成员、角色和业务资源管理", 80);
    Role memberRole = ensureRole(tenant.getId(), "普通成员", MEMBER_ROLE_AUTHORITY, "使用被授予的日常业务功能", 10);
    ensureUserRole(tenant.getId(), owner.getId(), ownerRole.getId());
    ensureUserRole(tenant.getId(), manager.getId(), managerRole.getId());
    ensureUserRole(tenant.getId(), member.getId(), memberRole.getId());

    reconcileCurrentResourceCatalog();
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
    application.setCode(code);
    application.setName(name);
    application.setDescription(description);
    application.setHomepage(homepage);
    application.setSort(sort);
    application.setEnabled(Boolean.TRUE);
    return hasText(application.getId())
        ? applicationRepository.updateById(application)
        : applicationRepository.save(application);
  }

  private Package ensurePackage(String code, String name, String description, int sort) {
    Package item = packageRepository.findAll(Map.of("code", code)).stream()
        .findFirst()
        .orElseGet(Package::new);
    item.setCode(code);
    item.setName(name);
    item.setDescription(description);
    item.setPrice(BigDecimal.ZERO);
    item.setDurationDays(null);
    item.setSort(sort);
    item.setEnabled(Boolean.TRUE);
    return hasText(item.getId()) ? packageRepository.updateById(item) : packageRepository.save(item);
  }

  private Tenant ensureOrganizationTenant(String ownerId) {
    Tenant tenant = tenantRepository.findAll(Map.of("name", properties.getOrganizationName())).stream()
        .findFirst()
        .orElseGet(Tenant::new);
    tenant.setName(properties.getOrganizationName());
    tenant.setDescription(properties.getOrganizationDescription());
    tenant.setOwnerId(ownerId);
    tenant.setTenantType(TenantType.ORGANIZATION);
    if (tenant.getAuthorizationVersion() == null) {
      tenant.setAuthorizationVersion(0L);
    }
    return hasText(tenant.getId()) ? tenantRepository.updateById(tenant) : tenantRepository.save(tenant);
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

  private void ensureTenantPackage(String tenantId, String packageCode) {
    if (normalizeCodes(tenantPackageRelevanceRepository.authorized(tenantId)).contains(packageCode)) {
      return;
    }
    TenantPackageRelevance relevance = new TenantPackageRelevance();
    relevance.setTenantId(tenantId);
    relevance.setPackageCode(packageCode);
    tenantPackageRelevanceRepository.saveAll(List.of(relevance));
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

  private void provisionPersonalPackage(User user) {
    tenantRepository.findPersonalTenantByOwnerId(user.getId())
        .map(Tenant::getId)
        .ifPresent(provisioner::provisionPersonalTenant);
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

  private void reconcileCurrentResourceCatalog() {
    Set<String> tenantCodes = grantableCodes(AuthorizationScopeType.TENANT);
    Set<String> personalCodes = grantableCodes(AuthorizationScopeType.PERSONAL);
    provisioner.provisionApplicationResources(
        "common",
        excludePrefix(tenantCodes, "ai."),
        excludePrefix(personalCodes, "ai.")
    );
    provisioner.provisionApplicationResources(
        "ai",
        selectByPrefix(tenantCodes, "ai."),
        selectByPrefix(personalCodes, "ai.")
    );
  }

  private Set<String> grantableCodes(AuthorizationScopeType scopeType) {
    Collection<String> accessible = resourceService.findAllAccessibleCodes(scopeType, false);
    return normalizeCodes(resourceService.filterGrantableAccessibleCodes(accessible, scopeType));
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
        .filter(BuiltInTenantWorkspaceInitializer::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
