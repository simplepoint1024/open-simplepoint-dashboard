package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Label;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.identity.UserDisplayName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned consistent pages; no held database snapshot or connection between RPC calls. */
@Service
@RemoteProvider
public class MemberLabelDirectoryImpl implements MemberLabelDirectory {
  private final JdbcTemplate jdbc;

  /** Composition binds the constructor to identity storage, never a host's primary source. */
  public MemberLabelDirectoryImpl(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  @Override
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Revision revision() {
    requireService();
    return readRevision();
  }

  @Override
  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.REPEATABLE_READ)
  public Page page(Request request) {
    requireService();
    if (request == null) {
      throw new IllegalArgumentException("A member label request is required");
    }
    // Revision and rows share one database snapshot even when a source mutation commits mid-read.
    Revision revision = readRevision();
    if (request.expectedRevision() != null && !request.expectedRevision().equals(revision)) {
      return new Page(request.tenantId(), revision, true, false, List.of(), null);
    }
    boolean active =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select exists (select 1 from simpoint_saas_tenants where id=? and"
                    + " deleted_at is null)",
                Boolean.class,
                request.tenantId()));
    if (!active) {
      return new Page(request.tenantId(), revision, false, false, List.of(), null);
    }
    String sql =
        "select u.id, "
            + UserDisplayName.SQL
            + " as name from simpoint_ac_users u where u.deleted_at is null and"
            + " coalesce(u.enabled,true)=true and exists (select 1 from simpoint_saas_tenants t"
            + " where t.id=? and t.deleted_at is null and (t.owner_id=u.id or exists"
            + " (select 1 from simpoint_saas_tenant_user_rel m where m.tenant_id=t.id and"
            + " m.user_id=u.id and m.deleted_at is null and coalesce(m.enabled,true)=true)))"
            + (request.afterUserId() == null ? "" : " and u.id > ?")
            + " order by u.id limit ?";
    Object[] args =
        request.afterUserId() == null
            ? new Object[] {request.tenantId(), request.limit() + 1}
            : new Object[] {request.tenantId(), request.afterUserId(), request.limit() + 1};
    var rows = jdbc.query(sql, MemberLabelDirectoryImpl::label, args);
    boolean more = rows.size() > request.limit();
    var page = more ? rows.subList(0, request.limit()) : rows;
    return new Page(
        request.tenantId(), revision, false, true, page, more ? page.getLast().userId() : null);
  }

  private Revision readRevision() {
    return jdbc.queryForObject(
        "select epoch, revision from simpoint_identity_member_label_revision where singleton=true",
        (row, index) -> new Revision(row.getString(1), row.getLong(2)));
  }

  private static Label label(ResultSet row, int index) throws SQLException {
    return new Label(row.getString(1), UserDisplayName.text(row.getString(2)));
  }

  private static void requireService() {
    if (ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .isEmpty()) {
      throw new AccessDeniedException("Member label snapshots require an authenticated service");
    }
  }
}
