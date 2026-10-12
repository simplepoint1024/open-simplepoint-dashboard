package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Literal-prefix global account picker; controller capabilities, not tenant membership, authorize this directory. */
@Repository
@Transactional(readOnly = true)
public class UserPickerQuery {
  private static final String DTO = UserPickerItem.class.getName();
  private final EntityManager em;

  /** Uses lightweight SQL projections and never hydrates account credentials. */
  public UserPickerQuery(EntityManager em) {
    this.em = em;
  }

  /** Counts and pages active, nondeleted accounts by readable email/phone literal prefixes. */
  public Page<UserPickerItem> search(String keyword, Pageable pageable) {
    requireIdentity();
    if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() > 50
        || pageable.getOffset() > Integer.MAX_VALUE || pageable.getSort().isSorted()) {
      throw new IllegalArgumentException("选人目录每页最多 50 条，使用固定顺序");
    }
    String search = keyword == null ? "" : keyword.trim();
    if (search.length() < 3) return Page.empty(pageable);
    if (search.length() > 320) throw new IllegalArgumentException("选人搜索前缀最多 320 个字符");
    String prefix = search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    List<String> fields = List.of("email", "phoneNumber").stream().filter(UserPickerQuery::readable).toList();
    if (fields.isEmpty()) throw new AccessDeniedException("当前字段权限不允许按邮箱或手机号搜索用户");
    String where = "u.deletedAt is null and u.enabled = true and (" + fields.stream()
        .map(field -> "lower(u." + field + ") like :prefix escape '!'").collect(Collectors.joining(" or ")) + ")";
    String name = displayName();
    var rows = em.createQuery(projection(name) + " where " + where + " order by lower(" + name + "), u.id", UserPickerItem.class)
        .setParameter("prefix", prefix).setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize()).getResultList();
    long total = em.createQuery("select count(u) from User u where " + where, Long.class).setParameter("prefix", prefix).getSingleResult();
    return new PageImpl<>(rows, pageable, total);
  }

  /** Disabled persisted values can still be displayed, but deleted accounts and unreadable IDs cannot. */
  public List<UserPickerItem> selected(Collection<String> ids) {
    requireIdentity();
    var requested = new LinkedHashSet<String>();
    if (ids != null) ids.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty()).forEach(requested::add);
    if (requested.size() > 100) throw new IllegalArgumentException("单次最多回显 100 个用户");
    if (requested.isEmpty()) return List.of();
    var rows = em.createQuery(projection(displayName()) + " where u.deletedAt is null and u.id in :ids", UserPickerItem.class)
        .setParameter("ids", requested).getResultList();
    var byId = rows.stream().collect(Collectors.toMap(UserPickerItem::id, row -> row));
    return requested.stream().map(byId::get).filter(Objects::nonNull).toList();
  }

  private static void requireIdentity() {
    if (!readable("id")) throw new AccessDeniedException("缺少用户标识查看权限，不能使用选人目录");
  }

  private static boolean readable(String field) {
    String access = FieldPermissionPolicy.access(User.class, field);
    return !FieldPermissionPolicy.hidden(access) && !"MASKED".equals(access);
  }

  private static String scalar(String field) {
    String access = FieldPermissionPolicy.access(User.class, field);
    return FieldPermissionPolicy.hidden(access) ? "cast(null as string)" : "MASKED".equals(access) ? "'***'" : "u." + field;
  }

  private static String displayName() {
    return org.simplepoint.security.identity.UserDisplayName.hql(UserPickerQuery::readable);
  }

  private static String projection(String name) {
    return "select new " + DTO + "(u.id, " + name + ", " + scalar("email") + ", " + scalar("phoneNumber")
        + ", " + scalar("picture") + ") from User u";
  }
}
