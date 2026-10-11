package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.simplepoint.core.*;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.jpa.DataScopePredicates;
import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.simplepoint.plugin.rbac.core.api.service.TenantMemberService;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.User;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TenantMemberServiceImpl implements TenantMemberService {
  private static final int ORGANIZATION_SUBTREE_BATCH = 500;
  private static final int MAX_ORGANIZATION_SUBTREE = 100_000;
  private final EntityManager em;
  private final ResourceAuthorizationVersionService versions;
  public TenantMemberServiceImpl(EntityManager em, ResourceAuthorizationVersionService versions) {
    this.em = em; this.versions = versions;
  }

  private Tenant tenant(boolean mutation) {
    return tenant(mutation, false);
  }

  private Tenant tenant(boolean mutation, boolean managerRequired) {
    var ctx = AuthorizationContextHolder.getContext();
    String id = ctx == null ? null : ctx.getAttribute("X-Tenant-Id");
    if (ctx == null || ctx.getScopeType() != AuthorizationScopeType.TENANT || id == null) {
      throw new AccessDeniedException("请先选择组织租户");
    }
    Tenant tenant = em.find(Tenant.class, id, mutation ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
    if (tenant == null || tenant.getDeletedAt() != null || tenant.getTenantType() != TenantType.ORGANIZATION) throw new AccessDeniedException("租户不存在");
    if (mutation || managerRequired) {
      AuthorizationScopeGuards.requireOrganizationTenantManager(ctx, tenant.getOwnerId());
    } else if (!Boolean.TRUE.equals(ctx.getIsAdministrator())
        && !ctx.getResources().contains("users.view")
        && !ctx.getResources().contains("organizations.view")) {
      throw new AccessDeniedException("缺少成员查看权限");
    }
    return tenant;
  }

  @Override
  public java.util.Map<String, Boolean> capabilities() {
    Tenant tenant = tenant(false);
    var context = AuthorizationContextHolder.getContext();
    boolean manage = AuthorizationScopeGuards.isTenantManager(context, tenant.getOwnerId());
    // Match both the assignment service's tenant-manager guard and UsersController's method authority.
    boolean configureRoles = manage && (Boolean.TRUE.equals(context.getIsAdministrator())
        || context.getResources().contains("users.config.role"));
    return java.util.Map.of("manage", manage, "configureRoles", configureRoles);
  }

  @Override
  public Page<TenantMemberView> list(java.util.Map<String, String> filters, Pageable pageable) {
    Tenant tenant = tenant(false);
    var input = org.simplepoint.data.jpa.ProjectionQuery.prepare(TenantMemberView.class, filters, pageable);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(TenantMemberView.class);
    var result = memberQuery(data, tenant.getId(), input);
    var fields = result.fields();
    data.select(cb.construct(TenantMemberView.class, fields.get("userId"), fields.get("name"), fields.get("email"),
        fields.get("orgId"), fields.get("orgName"), fields.get("enabled"), fields.get("userType"),
        result.member().get("revision")))
        .orderBy(input.orders(cb, fields, result.member().get("id")));
    var rows = em.createQuery(data).setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = memberQuery(count, tenant.getId(), input);
    count.select(cb.count(counted.member()));
    return new PageImpl<>(rows, pageable, em.createQuery(count).getSingleResult());
  }

  @Override
  public Page<TenantMemberView> organizationMembers(String orgId, String keyword, Pageable pageable) {
    Tenant tenant = tenant(false);
    requireOrganization(tenant.getId(), orgId);
    return memberPage(tenant.getId(), orgId, keyword, pageable, true);
  }

  @Override
  public Page<TenantMemberView> organizationCandidates(String orgId, String keyword, Pageable pageable) {
    Tenant tenant = tenant(false, true);
    requireOrganization(tenant.getId(), orgId);
    return memberPage(tenant.getId(), orgId, keyword, pageable, false);
  }

  private Page<TenantMemberView> memberPage(String tenantId, String orgId, String keyword,
      Pageable page, boolean subtree) {
    if (page == null || page.isUnpaged() || page.getPageSize() < 1 || page.getPageSize() > 100
        || page.getOffset() > Integer.MAX_VALUE || page.getSort().isSorted()) {
      throw new IllegalArgumentException("每页最多 100 条，使用固定成员顺序");
    }
    String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    if (search.length() > 128) throw new IllegalArgumentException("搜索词不能超过 128 个字符");
    if (!search.isEmpty() && java.util.List.of("name", "email", "userId").stream().anyMatch(field -> {
      String access = org.simplepoint.core.jackson.FieldPermissionPolicy.access(TenantMemberView.class, field);
      return org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access) || "MASKED".equals(access);
    })) throw new AccessDeniedException("当前字段权限不允许搜索成员信息");
    DataScopeCondition dataScope = organizationScope();
    return scopedMemberPage(tenantId, orgId, search, page, subtree, dataScope);
  }

  private Page<TenantMemberView> scopedMemberPage(String tenantId, String orgId, String search,
      Pageable page, boolean subtree, DataScopeCondition scope) {
    Set<String> subtreeIds = subtree ? organizationSubtree(tenantId, orgId) : Set.of();
    var input = org.simplepoint.data.jpa.ProjectionQuery.prepare(TenantMemberView.class, java.util.Map.of(), page);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(TenantMemberView.class);
    var result = memberQuery(data, tenantId, input);
    data.where(cb.and(data.getRestriction(), memberPageScope(data, result, tenantId, orgId,
        search, subtree, subtreeIds, scope)));
    var fields = result.fields();
    data.select(cb.construct(TenantMemberView.class, fields.get("userId"), fields.get("name"),
        fields.get("email"), fields.get("orgId"), fields.get("orgName"), fields.get("enabled"),
        fields.get("userType"), result.member().get("revision")))
        .orderBy(cb.asc(cb.lower(fields.get("name").as(String.class))),
            cb.asc(result.member().get("userId")));
    var rows = em.createQuery(data).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = memberQuery(count, tenantId, input);
    count.select(cb.count(counted.member())).where(cb.and(count.getRestriction(),
        memberPageScope(count, counted, tenantId, orgId, search, subtree, subtreeIds, scope)));
    return new PageImpl<>(rows, page, em.createQuery(count).getSingleResult());
  }

  private Predicate memberPageScope(CriteriaQuery<?> query, MemberQuery result, String tenantId,
      String orgId, String search, boolean subtree, Set<String> subtreeIds, DataScopeCondition scope) {
    var cb = em.getCriteriaBuilder();
    var member = result.member();
    var predicates = new ArrayList<Predicate>();
    predicates.add(subtree ? member.get("orgId").in(subtreeIds)
        : cb.or(cb.isNull(member.get("orgId")), cb.notEqual(member.get("orgId"), orgId)));
    if (scope != null && !scope.isAllData()) {
    var visibleOrg = query.subquery(Integer.class);
    var org = visibleOrg.from(Organization.class);
    var authorized = DataScopePredicates.compile(scope, org, cb, em.getMetamodel());
    visibleOrg.select(cb.literal(1)).where(cb.equal(org.get("id"), member.get("orgId")),
        cb.equal(org.get("tenantId"), tenantId), cb.isNull(org.get("deletedAt")),
        authorized == null ? cb.conjunction() : authorized);
    predicates.add(cb.exists(visibleOrg));
    }
    if (!search.isEmpty()) {
      String pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%")
          .replace("_", "\\_") + "%";
      predicates.add(cb.or(cb.like(cb.lower(result.fields().get("name").as(String.class)), pattern, '\\'),
          cb.like(cb.lower(result.fields().get("email").as(String.class)), pattern, '\\'),
          cb.like(cb.lower(result.fields().get("userId").as(String.class)), pattern, '\\')));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private Set<String> organizationSubtree(String tenantId, String rootId) {
    Set<String> visited = new LinkedHashSet<>();
    Set<String> frontier = Set.of(rootId);
    while (!frontier.isEmpty()) {
      var next = new LinkedHashSet<String>();
      var values = new ArrayList<>(frontier);
      for (int offset = 0; offset < values.size(); offset += ORGANIZATION_SUBTREE_BATCH) {
        var batch = values.subList(offset, Math.min(values.size(), offset + ORGANIZATION_SUBTREE_BATCH));
        next.addAll(em.createQuery("select o.id from Organization o where o.tenantId=:tenant"
            + " and o.deletedAt is null and o.parentId in :parents", String.class)
            .setParameter("tenant", tenantId).setParameter("parents", batch).getResultList());
      }
      visited.addAll(frontier);
      next.removeAll(visited);
      if (visited.size() + next.size() > MAX_ORGANIZATION_SUBTREE)
        throw new IllegalArgumentException("组织子树超出单次查询上限");
      frontier = next;
    }
    return visited;
  }

  private DataScopeCondition organizationScope() {
    var context = AuthorizationContextHolder.getContext();
    var rule = context == null ? null : context.activeDataScope();
    if (rule == null || !"organizations.view".equals(ResourcePolicyContext.get())) return null;
    return rule.condition("id", "createdBy", context);
  }

  private void requireOrganization(String tenantId, String orgId) {
    if (orgId == null || orgId.isBlank()) throw new IllegalArgumentException("请选择组织");
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(Long.class);
    var org = query.from(Organization.class);
    var authorized = DataScopePredicates.compile(organizationScope(), org, cb, em.getMetamodel());
    query.select(cb.count(org)).where(cb.equal(org.get("id"), orgId),
        cb.equal(org.get("tenantId"), tenantId), cb.isNull(org.get("deletedAt")),
        authorized == null ? cb.conjunction() : authorized);
    var count = em.createQuery(query).getSingleResult();
    if (count != 1) throw new AccessDeniedException("组织不存在或不属于当前租户");
  }

  private record MemberQuery(jakarta.persistence.criteria.Root<TenantUserRelevance> member,
                             java.util.Map<String, jakarta.persistence.criteria.Expression<?>> fields) {}

  private MemberQuery memberQuery(jakarta.persistence.criteria.CriteriaQuery<?> query, String tenantId,
                                   org.simplepoint.data.jpa.ProjectionQuery input) {
    var cb = em.getCriteriaBuilder();
    var member = query.from(TenantUserRelevance.class);
    var user = query.from(User.class);
    var organizationName = query.subquery(String.class);
    var organization = organizationName.from(Organization.class);
    organizationName.select(organization.get("name")).where(cb.equal(organization.get("id"), member.get("orgId")),
        cb.equal(organization.get("tenantId"), tenantId), cb.isNull(organization.get("deletedAt")));
    java.util.Map<String, jakarta.persistence.criteria.Expression<?>> fields = java.util.Map.of(
        "userId", user.get("id").as(String.class), "name", cb.<String>coalesce().value(user.get("nickname"))
            .value(user.get("name")).value(user.get("email")).value(user.get("id")).as(String.class),
        "email", user.get("email"), "orgId", member.get("orgId"), "orgName", organizationName,
        "enabled", cb.coalesce(member.<Boolean>get("enabled"), true).as(Boolean.class),
        "userType", member.get("userType"));
    query.where(cb.equal(member.get("tenantId"), tenantId), cb.equal(member.get("userId"), user.get("id")),
        cb.isNull(member.get("deletedAt")), cb.isNull(user.get("deletedAt")), input.predicate(cb, fields));
    return new MemberQuery(member, fields);
  }

  @Override
  @Transactional
  public void add(TenantMemberCommand command) {
    Tenant tenant = tenant(true);
    if (command == null || command.email() == null || command.email().isBlank()) throw new IllegalArgumentException("请输入已有账号的完整邮箱");
    User user = em.createQuery("select u from User u where lower(u.email)=:email and u.deletedAt is null and u.enabled=true", User.class)
        .setParameter("email", command.email().trim().toLowerCase(java.util.Locale.ROOT)).getResultStream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未找到可添加的账号，请先完成注册"));
    if (em.createQuery("select count(m) from TenantUserRelevance m where m.tenantId=:tenant and m.userId=:user", Long.class)
        .setParameter("tenant", tenant.getId()).setParameter("user", user.getId()).getSingleResult() > 0) {
      throw new IllegalArgumentException("账号已经是租户成员");
    }
    validateOrg(tenant.getId(), command.orgId());
    TenantUserRelevance member = new TenantUserRelevance();
    member.setTenantId(tenant.getId()); member.setUserId(user.getId());
    member.setOrgId(command.orgId() == null || command.orgId().isBlank() ? null : command.orgId()); member.setEnabled(true);
    member.setUserType(userType(command.userType(), "INTERNAL"));
    assertMemberAllowed(member);
    assertMemberWritable(null, command);
    em.persist(member);
    versions.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional
  public void update(String userId, TenantMemberCommand command) {
    Tenant tenant = tenant(true);
    TenantUserRelevance member = member(tenant.getId(), userId);
    assertMemberAllowed(member);
    assertMemberWritable(member, command);
    if (!Objects.equals(command.revision(), member.getRevision())) throw new OptimisticLockingFailureException("成员已发生变更，请刷新后重试");
    if (Objects.equals(userId, tenant.getOwnerId()) && Boolean.FALSE.equals(command.enabled())) {
      throw new AccessDeniedException("不能停用租户所有者");
    }
    validateOrg(tenant.getId(), command.orgId());
    member.setOrgId(command.orgId());
    if (command.enabled() != null) member.setEnabled(command.enabled());
    if (command.userType() != null) member.setUserType(userType(command.userType(), member.getUserType()));
    assertMemberAllowed(member);
    versions.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional
  public void removeFromOrganization(String userId,
      org.simplepoint.plugin.rbac.core.api.pojo.command.OrganizationMemberRemoval command) {
    Tenant tenant = tenant(true);
    var context = AuthorizationContextHolder.getContext();
    if (!Boolean.TRUE.equals(context.getIsAdministrator())
        && !context.getResources().contains("organizations.members.remove"))
      throw new AccessDeniedException("缺少移出组织权限");
    if (command == null || command.revision() == null) throw new IllegalArgumentException("请刷新成员列表后重试");
    requireOrganization(tenant.getId(), command.orgId());
    TenantUserRelevance member = member(tenant.getId(), userId);
    if (!Objects.equals(command.revision(), member.getRevision()))
      throw new OptimisticLockingFailureException("成员已发生变更，请刷新后重试");
    if (member.getOrgId() == null || !organizationSubtree(tenant.getId(), command.orgId()).contains(member.getOrgId()))
      throw new AccessDeniedException("用户已不在所选组织及下级组织中");
    assertMemberAllowed(member);
    assertMemberWritable(member, new TenantMemberCommand(null, null, null, command.revision(), null));
    // A deliberate departure may leave the caller's scope. Authorize the source before detaching.
    member.setOrgId(null);
    versions.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional
  public void remove(String userId) {
    Tenant tenant = tenant(true);
    if (Objects.equals(userId, tenant.getOwnerId())) throw new AccessDeniedException("请先转移租户所有权");
    TenantUserRelevance member = member(tenant.getId(), userId);
    assertMemberAllowed(member);
    em.createQuery("delete from UserRoleRelevance r where r.tenantId=:tenant and r.userId=:user")
        .setParameter("tenant", tenant.getId()).setParameter("user", userId).executeUpdate();
    em.remove(member);
    versions.refreshTenant(tenant.getId());
  }

  private TenantUserRelevance member(String tenantId, String userId) {
    return em.createQuery("select m from TenantUserRelevance m where m.tenantId=:tenant and m.userId=:user and m.deletedAt is null", TenantUserRelevance.class)
        .setParameter("tenant", tenantId).setParameter("user", userId).getResultStream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("成员不存在"));
  }

  private void validateOrg(String tenantId, String orgId) {
    if (orgId == null || orgId.isBlank()) return;
    var count = em.createQuery("select count(o) from Organization o where o.id=:org and o.tenantId=:tenant and o.deletedAt is null and o.enabled=true", Long.class)
        .setParameter("org", orgId).setParameter("tenant", tenantId).getSingleResult();
    if (count != 1) throw new AccessDeniedException("组织不存在或不属于当前租户");
  }

  private void assertMemberAllowed(TenantUserRelevance member) {
    var context = AuthorizationContextHolder.getContext();
    var scope = context == null ? null : context.activeDataScope();
    if (scope == null || "ALL".equals(scope.type())) return;
    if ("organizations.view".equals(ResourcePolicyContext.get())) {
      requireOrganization(member.getTenantId(), member.getOrgId());
      return;
    }
    boolean self = Objects.equals(member.getUserId(), context.getUserId());
    var account = em.find(User.class, member.getUserId());
    java.util.function.Function<String, Object> values = field -> switch (field) {
      case "userId" -> member.getUserId(); case "orgId" -> member.getOrgId();
      case "enabled" -> member.getEnabled(); case "userType" -> member.getUserType();
      case "email" -> account == null ? null : account.getEmail();
      case "name" -> account == null ? null : account.getNickname() != null ? account.getNickname()
          : account.getName() != null ? account.getName() : account.getEmail() != null ? account.getEmail() : account.getId();
      case "orgName" -> { var org = member.getOrgId() == null ? null : em.find(Organization.class, member.getOrgId());
        yield org == null || !Objects.equals(org.getTenantId(), member.getTenantId()) || org.getDeletedAt() != null ? null : org.getName(); }
      default -> throw new AccessDeniedException("成员权限字段映射无效");
    };
    if (scope.condition("orgId", "userId", context).getExpressions().stream().anyMatch(node ->
        org.simplepoint.core.permission.PermissionExpressionEvaluator.matches(node, values))) return;
    boolean allowed = switch (scope.type()) {
      case "SELF" -> self;
      case "DEPT", "DEPT_AND_BELOW", "CUSTOM" -> member.getOrgId() != null && scope.departmentIds().contains(member.getOrgId())
          || scope.includeSelf() && self;
      default -> false;
    };
    if (!allowed) throw new AccessDeniedException("成员不在当前数据权限范围内");
  }

  private void assertMemberWritable(TenantUserRelevance existing, TenantMemberCommand command) {
    var values = new java.util.LinkedHashMap<String, Object>();
    if (existing == null || !Objects.equals(existing.getOrgId(), command.orgId())) values.put("orgId", command.orgId());
    if (command.enabled() != null && (existing == null || !Objects.equals(existing.getEnabled(), command.enabled()))) values.put("enabled", command.enabled());
    if (command.userType() != null && (existing == null || !Objects.equals(existing.getUserType(), command.userType()))) values.put("userType", command.userType());
    if (existing == null) values.put("email", command.email());
    values.forEach((field, value) -> {
      if (!org.simplepoint.core.jackson.FieldPermissionPolicy.writable(TenantMemberView.class, field))
        throw new AccessDeniedException("字段不允许修改: " + field);
    });
  }

  private static String userType(String requested, String fallback) {
    if (requested == null || requested.isBlank()) return fallback;
    String normalized = requested.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("INTERNAL", "EXTERNAL", "REFERENCE").contains(normalized)) {
      throw new IllegalArgumentException("不支持的成员用户类型");
    }
    return normalized;
  }
}
