package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.*;
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
import org.simplepoint.security.entity.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.*;
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

  public PlatformAccountServiceImpl(EntityManager em, UsersService users, PlatformStepUpService stepUp,
                                    TenantRepository tenants, ResourceAuthorizationVersionService versions,
                                    DetailsProviderService detailsProviderService) {
    this.em = em; this.users = users; this.stepUp = stepUp; this.tenants = tenants; this.versions = versions;
    this.tableSchemas = new AnnotatedTableSchemaProvider(detailsProviderService);
  }

  @Override
  public Page<PlatformAccountView> list(Map<String, String> attributes, Pageable pageable) {
    require("platform.accounts.view", false);
    Pageable page = bounded(pageable);
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<User> query = cb.createQuery(User.class);
    Root<User> root = query.from(User.class);
    query.select(root).where(accountPredicates(cb, root, attributes));
    query.orderBy(orders(cb, root, page.getSort(), accountSorts(), "createdAt"));
    List<User> rows = em.createQuery(query).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();

    CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
    Root<User> countRoot = countQuery.from(User.class);
    countQuery.select(cb.count(countRoot)).where(accountPredicates(cb, countRoot, attributes));
    long count = em.createQuery(countQuery).getSingleResult();
    return new PageImpl<>(rows.stream().map(this::view).toList(), page, count);
  }

  @Override
  public Map<String, Object> accountSchema() {
    require("platform.accounts.view", false);
    return tableSchemas.schema(PlatformAccountView.class, AuthorizationContextHolder.getContext());
  }

  @Override
  public Map<String, Object> auditSchema() {
    require("platform.audit.view", false);
    return tableSchemas.schema(PlatformSecurityAudit.class, AuthorizationContextHolder.getContext());
  }

  @Override
  public Map<String, Object> capabilities() {
    var ctx = AuthorizationContextHolder.getContext();
    if (ctx == null || ctx.getScopeType() != AuthorizationScopeType.PLATFORM) throw new AccessDeniedException("请切换至平台工作台");
    User actor = account(ctx.getUserId());
    em.refresh(actor);
    if (!available(actor)) throw new AccessDeniedException("当前账号不可用");
    var identity = em.find(PlatformIdentity.class, actor.getId());
    if (identity != null) em.refresh(identity);
    Set<String> permissions = new HashSet<>();
    roles(actor.getId()).forEach(role -> permissions.addAll(role.permissions()));
    if (Boolean.TRUE.equals(actor.getSuperAdmin())) {
      for (PlatformRole role : PlatformRole.values()) permissions.addAll(role.permissions());
      permissions.add("platform.identity.manage");
    }
    return Map.of("permissions", permissions, "twoFactorEnabled", Boolean.TRUE.equals(actor.getTwoFactorEnabled()));
  }

  @Override
  @Transactional
  public PlatformAccountView create(PlatformAccountCommand command) {
    User actor = require("platform.accounts.create", false);
    rejectIdentityFields(command);
    reason(command);
    if (command.getEmail() == null || !command.getEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
      throw new IllegalArgumentException("请输入有效邮箱");
    }
    if (command.getInitialPassword() == null || command.getInitialPassword().length() < 12
        || command.getInitialPassword().length() > 64) throw new IllegalArgumentException("初始密码需要 12 至 64 个字符");
    stepUp.verify(actor.getId(), command);
    lock();
    actor = require("platform.accounts.create", false);
    User account = new User();
    account.setEmail(command.getEmail().trim().toLowerCase(Locale.ROOT));
    account.setName(name(command.getName()));
    account.setPassword(command.getInitialPassword());
    account.setSuperAdmin(false);
    account = users.create(account);
    audit(actor.getId(), account, "ACCOUNT_CREATED", "", command.getReason());
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
    String before = snapshot(account);
    if (Boolean.FALSE.equals(command.getEnabled()) && Boolean.TRUE.equals(account.getSuperAdmin())) protectLastRoot(account);
    if (command.getName() != null) account.setName(name(command.getName()));
    if (command.getEnabled() != null) account.setEnabled(command.getEnabled());
    bump(account);
    audit(actor.getId(), account, "ACCOUNT_UPDATED", before, command.getReason());
    return view(account);
  }

  @Override
  @Transactional
  public PlatformAccountView assignIdentity(String userId, PlatformAccountCommand command) {
    User actor = require("platform.identity.manage", true);
    reason(command);
    if (command.getRoles() == null || command.getRoles().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("平台角色集合不能为空");
    stepUp.verify(actor.getId(), command);
    lock();
    actor = require("platform.identity.manage", true);
    User account = account(userId);
    revision(account, command);
    String before = snapshot(account);
    if (Boolean.FALSE.equals(command.getSuperAdmin()) && Boolean.TRUE.equals(account.getSuperAdmin())) protectLastRoot(account);
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
    if (command.getSuperAdmin() != null) account.setSuperAdmin(command.getSuperAdmin());
    bump(account);
    audit(actor.getId(), account, "PLATFORM_IDENTITY_CHANGED", before, command.getReason());
    return view(account);
  }

  @Override
  public Page<PlatformSecurityAudit> audit(Map<String, String> attributes, Pageable pageable) {
    require("platform.audit.view", false);
    Pageable page = bounded(pageable);
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<PlatformSecurityAudit> query = cb.createQuery(PlatformSecurityAudit.class);
    Root<PlatformSecurityAudit> root = query.from(PlatformSecurityAudit.class);
    query.select(root).where(predicates(cb, root, attributes, auditFilters()));
    query.orderBy(orders(cb, root, page.getSort(), auditSorts(), "occurredAt"));
    var rows = em.createQuery(query).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();

    CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
    Root<PlatformSecurityAudit> countRoot = countQuery.from(PlatformSecurityAudit.class);
    countQuery.select(cb.count(countRoot)).where(predicates(cb, countRoot, attributes, auditFilters()));
    long count = em.createQuery(countQuery).getSingleResult();
    return new PageImpl<>(rows, page, count);
  }

  private Predicate[] accountPredicates(CriteriaBuilder cb, Root<User> root, Map<String, String> attributes) {
    List<Predicate> result = new ArrayList<>(List.of(predicates(cb, root, attributes, accountFilters())));
    result.add(cb.isNull(root.get("deletedAt")));
    String keyword = attributes == null ? null : attributes.get("keyword");
    if (keyword != null && !keyword.isBlank()) {
      String value = keyword.trim();
      if (value.length() > 100) throw new IllegalArgumentException("搜索条件过长");
      String pattern = "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
      result.add(cb.or(
          cb.like(cb.lower(root.get("name")), pattern, '\\'),
          cb.like(cb.lower(root.get("email")), pattern, '\\'),
          cb.equal(root.get("id"), value)
      ));
    }
    return result.toArray(Predicate[]::new);
  }

  private Map<String, Class<?>> accountFilters() {
    return Map.of("id", String.class, "name", String.class, "email", String.class,
        "enabled", Boolean.class, "superAdmin", Boolean.class, "revision", Long.class);
  }

  private Map<String, String> accountSorts() {
    return Map.of("id", "id", "name", "name", "email", "email", "enabled", "enabled",
        "superAdmin", "superAdmin", "revision", "authorizationVersion");
  }

  private Map<String, Class<?>> auditFilters() {
    return Map.of("id", String.class, "actorId", String.class, "targetId", String.class,
        "action", String.class, "beforeState", String.class, "afterState", String.class,
        "reason", String.class, "occurredAt", Instant.class);
  }

  private Map<String, String> auditSorts() {
    return Map.of("id", "id", "actorId", "actorId", "targetId", "targetId", "action", "action",
        "beforeState", "beforeState", "afterState", "afterState", "reason", "reason",
        "occurredAt", "occurredAt");
  }

  private <T> Predicate[] predicates(CriteriaBuilder cb, Root<T> root, Map<String, String> attributes,
                                     Map<String, Class<?>> allowed) {
    if (attributes == null || attributes.isEmpty()) return new Predicate[0];
    List<Predicate> result = new ArrayList<>();
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      String name = entry.getKey();
      if (Set.of("page", "size", "sort", "keyword").contains(name)) continue;
      Class<?> type = allowed.get(name);
      if (type == null) throw new IllegalArgumentException("不支持的筛选字段: " + name);
      result.add(filterPredicate(cb, root.get(name.equals("revision") ? "authorizationVersion" : name),
          type, parseFilter(entry.getValue())));
    }
    return result.toArray(Predicate[]::new);
  }

  private Predicate filterPredicate(CriteriaBuilder cb, Path<?> path, Class<?> type, Filter filter) {
    if ("is:null".equals(filter.operator())) return cb.isNull(path);
    if ("is:not:null".equals(filter.operator())) return cb.isNotNull(path);
    if (filter.value() == null || filter.value().isBlank()) throw new IllegalArgumentException("筛选值不能为空");
    if (type == String.class) return stringPredicate(cb, path.as(String.class), filter);
    if (type == Boolean.class) return comparablePredicate(cb, path.as(Boolean.class), filter, Boolean::valueOf);
    if (type == Long.class) return comparablePredicate(cb, path.as(Long.class), filter, Long::valueOf);
    if (type == Instant.class) return comparablePredicate(cb, path.as(Instant.class), filter, Instant::parse);
    throw new IllegalArgumentException("不支持的筛选类型");
  }

  private Predicate stringPredicate(CriteriaBuilder cb, Expression<String> path, Filter filter) {
    String value = filter.value().trim().toLowerCase(Locale.ROOT);
    Expression<String> normalized = cb.lower(path);
    return switch (filter.operator()) {
      case "like" -> cb.like(normalized, "%" + escapeLike(value) + "%", '\\');
      case "not:like" -> cb.notLike(normalized, "%" + escapeLike(value) + "%", '\\');
      case "equals" -> cb.equal(normalized, value);
      case "not:equals" -> cb.notEqual(normalized, value);
      case "in" -> normalized.in(split(value));
      case "not:in" -> cb.not(normalized.in(split(value)));
      case "than:greater" -> cb.greaterThan(normalized, value);
      case "than:less" -> cb.lessThan(normalized, value);
      case "than:equal:greater" -> cb.greaterThanOrEqualTo(normalized, value);
      case "than:equal:less" -> cb.lessThanOrEqualTo(normalized, value);
      case "between", "not:between" -> between(cb, normalized, filter, item -> item);
      default -> throw new IllegalArgumentException("不支持的筛选操作: " + filter.operator());
    };
  }

  private <T extends Comparable<? super T>> Predicate comparablePredicate(
      CriteriaBuilder cb, Expression<T> path, Filter filter, java.util.function.Function<String, T> converter) {
    String value = filter.value().trim();
    return switch (filter.operator()) {
      case "equals" -> cb.equal(path, converter.apply(value));
      case "not:equals" -> cb.notEqual(path, converter.apply(value));
      case "in" -> path.in(split(value).stream().map(converter).toList());
      case "not:in" -> cb.not(path.in(split(value).stream().map(converter).toList()));
      case "than:greater" -> cb.greaterThan(path, converter.apply(value));
      case "than:less" -> cb.lessThan(path, converter.apply(value));
      case "than:equal:greater" -> cb.greaterThanOrEqualTo(path, converter.apply(value));
      case "than:equal:less" -> cb.lessThanOrEqualTo(path, converter.apply(value));
      case "between", "not:between" -> between(cb, path, filter, converter);
      default -> throw new IllegalArgumentException("不支持的筛选操作: " + filter.operator());
    };
  }

  private <T extends Comparable<? super T>> Predicate between(CriteriaBuilder cb, Expression<T> path,
                                                               Filter filter,
                                                               java.util.function.Function<String, T> converter) {
    List<String> values = split(filter.value());
    if (values.size() != 2) throw new IllegalArgumentException("区间筛选需要两个值");
    Predicate predicate = cb.between(path, converter.apply(values.get(0)), converter.apply(values.get(1)));
    return "not:between".equals(filter.operator()) ? cb.not(predicate) : predicate;
  }

  private <T> List<jakarta.persistence.criteria.Order> orders(CriteriaBuilder cb, Root<T> root, Sort sort,
                                                               Map<String, String> allowed, String fallback) {
    List<jakarta.persistence.criteria.Order> result = new ArrayList<>();
    for (Sort.Order order : sort) {
      String property = allowed.get(order.getProperty());
      if (property == null) throw new IllegalArgumentException("不支持的排序字段: " + order.getProperty());
      result.add(order.isAscending() ? cb.asc(root.get(property)) : cb.desc(root.get(property)));
    }
    if (result.isEmpty()) result.add(cb.desc(root.get(fallback)));
    result.add(cb.asc(root.get("id")));
    return result;
  }

  private Filter parseFilter(String raw) {
    if (raw == null || raw.length() > 2100) throw new IllegalArgumentException("筛选条件无效");
    for (String operator : List.of("than:equal:greater", "than:equal:less", "than:greater", "than:less",
        "not:between", "not:equals", "not:like", "not:in", "is:not:null", "is:null",
        "between", "equals", "like", "in")) {
      if (raw.equals(operator)) return new Filter(operator, null);
      if (raw.startsWith(operator + ":")) return new Filter(operator, raw.substring(operator.length() + 1));
    }
    throw new IllegalArgumentException("筛选操作无效");
  }

  private List<String> split(String value) {
    return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record Filter(String operator, String value) { }

  private User require(String permission, boolean rootOnly) {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getScopeType() != AuthorizationScopeType.PLATFORM || context.getUserId() == null) {
      throw new AccessDeniedException("请切换至平台工作台");
    }
    User actor = account(context.getUserId());
    // Refresh after the global lock: never authorize a mutation using an earlier request snapshot.
    em.refresh(actor);
    if (!available(actor)) throw new AccessDeniedException("当前账号不可用");
    if (Boolean.TRUE.equals(actor.getSuperAdmin())) return actor;
    var currentIdentity = em.find(PlatformIdentity.class, actor.getId());
    if (currentIdentity != null) em.refresh(currentIdentity);
    if (!rootOnly && roles(actor.getId()).stream().anyMatch(role -> role.permissions().contains(permission))) return actor;
    throw new AccessDeniedException(rootOnly ? "仅超级管理员可以管理平台身份" : "缺少平台操作权限");
  }

  private boolean available(User user) {
    return Boolean.TRUE.equals(user.getEnabled()) && Boolean.TRUE.equals(user.getAccountNonLocked())
        && Boolean.TRUE.equals(user.getAccountNonExpired()) && Boolean.TRUE.equals(user.getCredentialsNonExpired());
  }

  private void lock() {
    if (em.find(PlatformSecurityState.class, "platform", LockModeType.PESSIMISTIC_WRITE) == null) {
      throw new AccessDeniedException("平台安全表未初始化，请先执行迁移");
    }
  }

  private User account(String id) {
    User account = id == null ? null : em.find(User.class, id);
    if (account == null || account.getDeletedAt() != null) throw new IllegalArgumentException("账号不存在");
    return account;
  }

  private Set<PlatformRole> roles(String id) {
    PlatformIdentity identity = em.find(PlatformIdentity.class, id);
    if (identity == null) return Set.of();
    return Set.copyOf(identity.getRoles());
  }

  private void revision(User account, PlatformAccountCommand command) {
    em.refresh(account);
    if (!Objects.equals(command.getRevision(), account.getAuthorizationVersion())) {
      throw new OptimisticLockingFailureException("账号已发生变更，请刷新后重试");
    }
  }

  private void protectLastRoot(User account) {
    if (!available(account)) return;
    long count = em.createQuery("select count(u) from User u where u.superAdmin = true and u.enabled = true and u.accountNonLocked = true and u.accountNonExpired = true and u.credentialsNonExpired = true and u.deletedAt is null", Long.class).getSingleResult();
    if (count <= 1) throw new AccessDeniedException("不能撤销或停用最后一个有效超级管理员");
  }

  private void bump(User account) {
    account.setAuthorizationVersion(account.getAuthorizationVersion() + 1);
    account.setUpdatedAt(Instant.now());
    versions.refreshTenants(tenants.getTenantsByUserId(account.getId()).stream().map(t -> t.tenantId()).toList());
  }

  private PlatformAccountView view(User user) {
    return new PlatformAccountView(user.getId(), user.getName(), user.getEmail(), Boolean.TRUE.equals(user.getEnabled()),
        Boolean.TRUE.equals(user.getSuperAdmin()), roles(user.getId()), user.getAuthorizationVersion());
  }

  private String snapshot(User user) {
    return "name=" + user.getName() + ";enabled=" + Boolean.TRUE.equals(user.getEnabled()) + ";superAdmin=" + Boolean.TRUE.equals(user.getSuperAdmin())
        + ";roles=" + roles(user.getId()).stream().map(Enum::name).sorted().toList()
        + ";revision=" + user.getAuthorizationVersion();
  }

  private void audit(String actorId, User user, String action, String before, String reason) {
    PlatformSecurityAudit entry = new PlatformSecurityAudit();
    entry.setActorId(actorId); entry.setTargetId(user.getId()); entry.setAction(action);
    entry.setBeforeState(before); entry.setAfterState(snapshot(user));
    entry.setReason(reason.trim()); entry.setOccurredAt(Instant.now());
    em.persist(entry);
  }

  private void rejectIdentityFields(PlatformAccountCommand command) {
    if (command == null || command.getSuperAdmin() != null || command.getRoles() != null) {
      throw new IllegalArgumentException("平台身份必须通过独立授权接口修改");
    }
  }

  private void reason(PlatformAccountCommand command) {
    if (command == null || command.getReason() == null || command.getReason().isBlank() || command.getReason().length() > 500) {
      throw new IllegalArgumentException("请填写 1 至 500 字的操作原因");
    }
  }

  private String name(String value) {
    if (value != null && value.length() > 100) throw new IllegalArgumentException("姓名不能超过 100 字");
    return value == null ? null : value.trim();
  }

  private Pageable bounded(Pageable pageable) {
    int number = pageable.isPaged() ? pageable.getPageNumber() : 0;
    if (number > 100000) throw new IllegalArgumentException("页码过大");
    return PageRequest.of(number, Math.max(1, Math.min(50, pageable.isPaged() ? pageable.getPageSize() : 20)),
        pageable.getSort());
  }
}
