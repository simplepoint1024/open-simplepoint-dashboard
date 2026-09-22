package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.plugin.rbac.core.api.pojo.command.PlatformAccountCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PlatformAccountView;
import org.simplepoint.plugin.rbac.core.api.service.PlatformAccountService;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.PlatformIdentity;
import org.simplepoint.security.entity.PlatformRole;
import org.simplepoint.security.entity.PlatformSecurityAudit;
import org.simplepoint.security.entity.PlatformSecurityState;
import org.simplepoint.security.entity.User;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform control plane. Every operation rechecks the actor's persisted identity. */
@Service
@Transactional(readOnly = true)
public class PlatformAccountServiceImpl implements PlatformAccountService {
  private final EntityManager em;
  private final UsersService users;
  private final PlatformStepUpService stepUp;
  private final TenantRepository tenants;
  private final ResourceAuthorizationVersionService versions;
  private final AnnotatedTableSchemaProvider tableSchemas;
  private final PlatformAccountQueries queries;

  /** Wires persisted identity checks and bounded platform query projections. */
  public PlatformAccountServiceImpl(
      EntityManager em,
      UsersService users,
      PlatformStepUpService stepUp,
      TenantRepository tenants,
      ResourceAuthorizationVersionService versions,
      DetailsProviderService detailsProviderService) {
    this.em = em;
    this.users = users;
    this.stepUp = stepUp;
    this.tenants = tenants;
    this.versions = versions;
    this.tableSchemas = new AnnotatedTableSchemaProvider(detailsProviderService);
    this.queries = new PlatformAccountQueries(em);
  }

  @Override
  public Page<PlatformAccountView> list(Map<String, String> attributes, Pageable pageable) {
    require("platform.accounts.view", false);
    return queries.accounts(attributes, pageable);
  }

  @Override
  public Map<String, Object> accountSchema() {
    require("platform.accounts.view", false);
    return tableSchemas.schema(PlatformAccountView.class, AuthorizationContextHolder.getContext());
  }

  @Override
  public Map<String, Object> auditSchema() {
    require("platform.audit.view", false);
    return tableSchemas.schema(
        PlatformSecurityAudit.class, AuthorizationContextHolder.getContext());
  }

  @Override
  public Map<String, Object> capabilities() {
    var ctx = AuthorizationContextHolder.getContext();
    if (ctx == null || ctx.getScopeType() != AuthorizationScopeType.PLATFORM) {
      throw new AccessDeniedException("请切换至平台工作台");
    }
    User actor = account(ctx.getUserId());
    em.refresh(actor);
    if (!available(actor)) {
      throw new AccessDeniedException("当前账号不可用");
    }
    var identity = em.find(PlatformIdentity.class, actor.getId());
    if (identity != null) {
      em.refresh(identity);
    }
    Set<String> permissions = new HashSet<>();
    roles(actor.getId()).forEach(role -> permissions.addAll(role.permissions()));
    if (Boolean.TRUE.equals(actor.getSuperAdmin())) {
      for (PlatformRole role : PlatformRole.values()) {
        permissions.addAll(role.permissions());
      }
      permissions.add("platform.identity.manage");
    }
    return Map.of(
        "permissions",
        permissions,
        "twoFactorEnabled",
        Boolean.TRUE.equals(actor.getTwoFactorEnabled()));
  }

  @Override
  @Transactional
  public PlatformAccountView create(PlatformAccountCommand command) {
    final User initialActor = require("platform.accounts.create", false);
    rejectIdentityFields(command);
    reason(command);
    if (command.getEmail() == null
        || !command.getEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
      throw new IllegalArgumentException("请输入有效邮箱");
    }
    if (command.getInitialPassword() == null
        || command.getInitialPassword().length() < 12
        || command.getInitialPassword().length() > 64) {
      throw new IllegalArgumentException("初始密码需要 12 至 64 个字符");
    }
    stepUp.verify(initialActor.getId(), command);
    lock();
    final User actor = require("platform.accounts.create", false);
    User account = new User();
    account.setEmail(command.getEmail().trim().toLowerCase(Locale.ROOT));
    account.setName(name(command.getName()));
    account.setPassword(command.getInitialPassword());
    account.setSuperAdmin(false);
    account = users.create(account);
    recordAudit(actor.getId(), account, "ACCOUNT_CREATED", "", command.getReason());
    return view(account);
  }

  @Override
  @Transactional
  public PlatformAccountView update(String userId, PlatformAccountCommand command) {
    User actor = require("platform.accounts.edit", false);
    rejectIdentityFields(command);
    reason(command);
    stepUp.verify(actor.getId(), command);
    lock();
    actor = require("platform.accounts.edit", false);
    User account = account(userId);
    if (!Boolean.TRUE.equals(actor.getSuperAdmin())
        && (Boolean.TRUE.equals(account.getSuperAdmin()) || !roles(userId).isEmpty())) {
      throw new AccessDeniedException("普通平台账号管理员不能修改平台特权账号");
    }
    revision(account, command);
    final String before = snapshot(account);
    if (Boolean.FALSE.equals(command.getEnabled()) && Boolean.TRUE.equals(account.getSuperAdmin())) {
      protectLastRoot(account);
    }
    if (command.getName() != null) {
      account.setName(name(command.getName()));
    }
    if (command.getEnabled() != null) {
      account.setEnabled(command.getEnabled());
    }
    bump(account);
    recordAudit(actor.getId(), account, "ACCOUNT_UPDATED", before, command.getReason());
    return view(account);
  }

  @Override
  @Transactional
  public PlatformAccountView assignIdentity(String userId, PlatformAccountCommand command) {
    User actor = require("platform.identity.manage", true);
    reason(command);
    if (command.getRoles() == null || command.getRoles().stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("平台角色集合不能为空");
    }
    stepUp.verify(actor.getId(), command);
    lock();
    actor = require("platform.identity.manage", true);
    User account = account(userId);
    revision(account, command);
    final String before = snapshot(account);
    if (Boolean.FALSE.equals(command.getSuperAdmin())
        && Boolean.TRUE.equals(account.getSuperAdmin())) {
      protectLastRoot(account);
    }
    if (Boolean.TRUE.equals(command.getSuperAdmin()) && !available(account)) {
      throw new IllegalArgumentException("不能将不可用账号设为超级管理员");
    }
    PlatformIdentity identity = em.find(PlatformIdentity.class, userId);
    if (identity == null) {
      identity = new PlatformIdentity();
      identity.setUserId(userId);
      em.persist(identity);
    } else {
      em.refresh(identity);
    }
    identity.getRoles().clear();
    identity.getRoles().addAll(command.getRoles());
    if (command.getSuperAdmin() != null) {
      account.setSuperAdmin(command.getSuperAdmin());
    }
    bump(account);
    recordAudit(actor.getId(), account, "PLATFORM_IDENTITY_CHANGED", before, command.getReason());
    return view(account);
  }

  @Override
  public Page<PlatformSecurityAudit> audit(Map<String, String> attributes, Pageable pageable) {
    require("platform.audit.view", false);
    return queries.audits(attributes, pageable);
  }

  private User require(String permission, boolean rootOnly) {
    var context = AuthorizationContextHolder.getContext();
    if (context == null
        || context.getScopeType() != AuthorizationScopeType.PLATFORM
        || context.getUserId() == null) {
      throw new AccessDeniedException("请切换至平台工作台");
    }
    User actor = account(context.getUserId());
    // Refresh after the global lock: never authorize a mutation using an earlier request snapshot.
    em.refresh(actor);
    if (!available(actor)) {
      throw new AccessDeniedException("当前账号不可用");
    }
    if (Boolean.TRUE.equals(actor.getSuperAdmin())) {
      return actor;
    }
    var currentIdentity = em.find(PlatformIdentity.class, actor.getId());
    if (currentIdentity != null) {
      em.refresh(currentIdentity);
    }
    if (!rootOnly
        && roles(actor.getId()).stream().anyMatch(role -> role.permissions().contains(permission))) {
      return actor;
    }
    throw new AccessDeniedException(rootOnly ? "仅超级管理员可以管理平台身份" : "缺少平台操作权限");
  }

  private boolean available(User user) {
    return Boolean.TRUE.equals(user.getEnabled())
        && Boolean.TRUE.equals(user.getAccountNonLocked())
        && Boolean.TRUE.equals(user.getAccountNonExpired())
        && Boolean.TRUE.equals(user.getCredentialsNonExpired());
  }

  private void lock() {
    if (em.find(PlatformSecurityState.class, "platform", LockModeType.PESSIMISTIC_WRITE) == null) {
      throw new AccessDeniedException("平台安全表未初始化，请先执行迁移");
    }
  }

  private User account(String id) {
    User account = id == null ? null : em.find(User.class, id);
    if (account == null || account.getDeletedAt() != null) {
      throw new IllegalArgumentException("账号不存在");
    }
    return account;
  }

  private Set<PlatformRole> roles(String id) {
    PlatformIdentity identity = em.find(PlatformIdentity.class, id);
    if (identity == null) {
      return Set.of();
    }
    return Set.copyOf(identity.getRoles());
  }

  private void revision(User account, PlatformAccountCommand command) {
    em.refresh(account);
    if (!Objects.equals(command.getRevision(), account.getAuthorizationVersion())) {
      throw new OptimisticLockingFailureException("账号已发生变更，请刷新后重试");
    }
  }

  private void protectLastRoot(User account) {
    if (!available(account)) {
      return;
    }
    long count =
        em.createQuery(
                "select count(u) from User u where u.superAdmin = true and u.enabled = true and"
                    + " u.accountNonLocked = true and u.accountNonExpired = true and"
                    + " u.credentialsNonExpired = true and u.deletedAt is null",
                Long.class)
            .getSingleResult();
    if (count <= 1) {
      throw new AccessDeniedException("不能撤销或停用最后一个有效超级管理员");
    }
  }

  private void bump(User account) {
    account.setAuthorizationVersion(account.getAuthorizationVersion() + 1);
    account.setUpdatedAt(Instant.now());
    versions.refreshTenants(
        tenants.getTenantsByUserId(account.getId()).stream().map(t -> t.tenantId()).toList());
  }

  private PlatformAccountView view(User user) {
    return new PlatformAccountView(
        user.getId(),
        user.getName(),
        user.getEmail(),
        Boolean.TRUE.equals(user.getEnabled()),
        Boolean.TRUE.equals(user.getSuperAdmin()),
        roles(user.getId()),
        user.getAuthorizationVersion());
  }

  private String snapshot(User user) {
    return "name="
        + user.getName()
        + ";enabled="
        + Boolean.TRUE.equals(user.getEnabled())
        + ";superAdmin="
        + Boolean.TRUE.equals(user.getSuperAdmin())
        + ";roles="
        + roles(user.getId()).stream().map(Enum::name).sorted().toList()
        + ";revision="
        + user.getAuthorizationVersion();
  }

  private void recordAudit(String actorId, User user, String action, String before, String reason) {
    PlatformSecurityAudit entry = new PlatformSecurityAudit();
    entry.setActorId(actorId);
    entry.setTargetId(user.getId());
    entry.setAction(action);
    entry.setBeforeState(before);
    entry.setAfterState(snapshot(user));
    entry.setReason(reason.trim());
    entry.setOccurredAt(Instant.now());
    em.persist(entry);
  }

  private void rejectIdentityFields(PlatformAccountCommand command) {
    if (command == null || command.getSuperAdmin() != null || command.getRoles() != null) {
      throw new IllegalArgumentException("平台身份必须通过独立授权接口修改");
    }
  }

  private void reason(PlatformAccountCommand command) {
    if (command == null
        || command.getReason() == null
        || command.getReason().isBlank()
        || command.getReason().length() > 500) {
      throw new IllegalArgumentException("请填写 1 至 500 字的操作原因");
    }
  }

  private String name(String value) {
    if (value != null && value.length() > 100) {
      throw new IllegalArgumentException("姓名不能超过 100 字");
    }
    return value == null ? null : value.trim();
  }
}
