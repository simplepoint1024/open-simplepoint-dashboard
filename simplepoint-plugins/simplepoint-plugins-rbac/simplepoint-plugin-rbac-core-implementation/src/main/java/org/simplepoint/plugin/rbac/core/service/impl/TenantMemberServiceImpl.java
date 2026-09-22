package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.simplepoint.core.*;
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
  private final EntityManager em;
  private final ResourceAuthorizationVersionService versions;
  public TenantMemberServiceImpl(EntityManager em, ResourceAuthorizationVersionService versions) {
    this.em = em; this.versions = versions;
  }

  private Tenant tenant(boolean mutation) {
    var ctx = AuthorizationContextHolder.getContext();
    String id = ctx == null ? null : ctx.getAttribute("X-Tenant-Id");
    if (ctx == null || ctx.getScopeType() != AuthorizationScopeType.TENANT || id == null) {
      throw new AccessDeniedException("请先选择组织租户");
    }
    Tenant tenant = em.find(Tenant.class, id, mutation ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
    if (tenant == null || tenant.getDeletedAt() != null || tenant.getTenantType() != TenantType.ORGANIZATION) throw new AccessDeniedException("租户不存在");
    if (mutation) {
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
    return java.util.Map.of("manage", AuthorizationScopeGuards.isTenantManager(
        AuthorizationContextHolder.getContext(), tenant.getOwnerId()));
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

  private static final String SUBTREE = """
      with recursive subtree(id) as (
        select id from simpoint_saas_organizations
          where id = :org and tenant_id = :tenant and deleted_at is null
        union
        select o.id from simpoint_saas_organizations o
          join subtree s on o.parent_id = s.id
          where o.tenant_id = :tenant and o.deleted_at is null
      )
      """;
  private static final String MEMBER_FROM = """
      from simpoint_saas_tenant_user_rel m
      join simpoint_ac_users u on u.id = m.user_id and u.deleted_at is null
      left join simpoint_saas_organizations o on o.id = m.org_id
        and o.tenant_id = :tenant and o.deleted_at is null
      where m.tenant_id = :tenant and m.deleted_at is null
        and (:keyword = '' or position(:keyword in lower(
          coalesce(u.nickname, '') || ' ' || coalesce(u.name, '') || ' '
          || coalesce(u.email, '') || ' ' || u.id)) > 0)
      """;

  @Override
  public Page<TenantMemberView> organizationMembers(String orgId, String keyword, Pageable pageable) {
    Tenant tenant = tenant(false);
    requireOrganization(tenant.getId(), orgId);
    return memberPage(tenant.getId(), orgId, keyword, pageable, true);
  }

  @Override
  public Page<TenantMemberView> organizationCandidates(String orgId, String keyword, Pageable pageable) {
    Tenant tenant = tenant(true);
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
    String scope = subtree ? " and m.org_id in (select id from subtree)"
        : " and (m.org_id is null or m.org_id <> :org)";
    var data = em.createNativeQuery((subtree ? SUBTREE : "") + """
        select m.user_id, coalesce(u.nickname, u.name, u.email, u.id), u.email,
          m.org_id, o.name, m.enabled, m.user_type, m.revision
        """ + MEMBER_FROM + scope
        + " order by lower(coalesce(u.nickname, u.name, u.email, u.id)), m.user_id"
        + " limit :limit offset :offset");
    data.setParameter("tenant", tenantId).setParameter("org", orgId)
        .setParameter("keyword", search).setParameter("limit", page.getPageSize())
        .setParameter("offset", (int) page.getOffset());
    @SuppressWarnings("unchecked")
    List<Object[]> raw = data.getResultList();
    List<TenantMemberView> rows = raw.stream().map(columns -> new TenantMemberView(
        (String) columns[0], (String) columns[1], (String) columns[2],
        (String) columns[3], (String) columns[4], Boolean.TRUE.equals(columns[5]),
        (String) columns[6], columns[7] == null ? null : ((Number) columns[7]).longValue())).toList();
    Number count = (Number) em.createNativeQuery((subtree ? SUBTREE : "") + "select count(*) "
        + MEMBER_FROM + scope).setParameter("tenant", tenantId).setParameter("org", orgId)
        .setParameter("keyword", search).getSingleResult();
    return new PageImpl<>(rows, page, count.longValue());
  }

  private void requireOrganization(String tenantId, String orgId) {
    if (orgId == null || orgId.isBlank()) throw new IllegalArgumentException("请选择组织");
    var count = em.createQuery("select count(o) from Organization o where o.id=:org and o.tenantId=:tenant and o.deletedAt is null", Long.class)
        .setParameter("org", orgId).setParameter("tenant", tenantId).getSingleResult();
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
    em.persist(member);
    versions.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional
  public void update(String userId, TenantMemberCommand command) {
    Tenant tenant = tenant(true);
    TenantUserRelevance member = member(tenant.getId(), userId);
    if (!Objects.equals(command.revision(), member.getRevision())) throw new OptimisticLockingFailureException("成员已发生变更，请刷新后重试");
    if (Objects.equals(userId, tenant.getOwnerId()) && Boolean.FALSE.equals(command.enabled())) {
      throw new AccessDeniedException("不能停用租户所有者");
    }
    validateOrg(tenant.getId(), command.orgId());
    member.setOrgId(command.orgId());
    if (command.enabled() != null) member.setEnabled(command.enabled());
    if (command.userType() != null) member.setUserType(userType(command.userType(), member.getUserType()));
    versions.refreshTenant(tenant.getId());
  }

  @Override
  @Transactional
  public void remove(String userId) {
    Tenant tenant = tenant(true);
    if (Objects.equals(userId, tenant.getOwnerId())) throw new AccessDeniedException("请先转移租户所有权");
    TenantUserRelevance member = member(tenant.getId(), userId);
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

  private static String userType(String requested, String fallback) {
    if (requested == null || requested.isBlank()) return fallback;
    String normalized = requested.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("INTERNAL", "EXTERNAL", "REFERENCE").contains(normalized)) {
      throw new IllegalArgumentException("不支持的成员用户类型");
    }
    return normalized;
  }
}
