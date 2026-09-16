package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
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
    } else if (!Boolean.TRUE.equals(ctx.getIsAdministrator()) && !ctx.getResources().contains("users.view")) {
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
  public Page<TenantMemberView> list(Pageable pageable) {
    Tenant tenant = tenant(false);
    if (pageable.isPaged() && pageable.getPageNumber() > 100000) throw new IllegalArgumentException("页码过大");
    var page = PageRequest.of(pageable.isPaged() ? pageable.getPageNumber() : 0,
        Math.min(50, pageable.isPaged() ? pageable.getPageSize() : 20));
    String from = " from TenantUserRelevance m join User u on u.id=m.userId"
        + " left join Organization o on o.id=m.orgId and o.tenantId=:tenant and o.deletedAt is null"
        + " where m.tenantId=:tenant and m.deletedAt is null and u.deletedAt is null";
    var rows = em.createQuery("select new org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView("
        + "u.id,coalesce(u.nickname,u.name,u.email,u.id),u.email,m.orgId,o.name,coalesce(m.enabled,true),m.revision)"
        + from + " order by u.id", TenantMemberView.class).setParameter("tenant", tenant.getId())
        .setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    var total = em.createQuery("select count(m) from TenantUserRelevance m"
        + " where m.tenantId=:tenant and m.deletedAt is null", Long.class)
        .setParameter("tenant", tenant.getId()).getSingleResult();
    return new PageImpl<>(rows, page, total);
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
}
