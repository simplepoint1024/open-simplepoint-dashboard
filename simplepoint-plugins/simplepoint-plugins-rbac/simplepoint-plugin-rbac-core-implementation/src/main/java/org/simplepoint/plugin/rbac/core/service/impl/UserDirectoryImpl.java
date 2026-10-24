package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryEntry;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryPage;
import org.simplepoint.plugin.rbac.core.api.service.UserDirectory;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.identity.UserDisplayName;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity owns directory SQL; only scalar projections cross the service boundary. */
@Service
@RemoteProvider
@Transactional(readOnly = true)
public class UserDirectoryImpl implements UserDirectory {

  private static final String PROJECTION =
      "select new "
          + UserDirectoryEntry.class.getName()
          + "(u.id, "
          + UserDisplayName.HQL
          + ", u.email, u.phoneNumber, coalesce(u.enabled, false)) from User u";

  private final EntityManager entityManager;

  /** Uses the identity module's storage and canonical display-name expression. */
  public UserDirectoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Optional<UserDirectoryEntry> findById(String userId) {
    requireServiceCaller();
    if (userId == null || userId.isBlank() || userId.length() > 64) {
      throw new IllegalArgumentException("Invalid user directory identifier");
    }
    return entityManager
        .createQuery(
            PROJECTION + " where u.deletedAt is null and u.id = :id", UserDirectoryEntry.class)
        .setParameter("id", userId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public UserDirectoryPage search(String keyword, int page, int size) {
    requireServiceCaller();
    long offset = (long) page * size;
    if (page < 0 || size < 1 || size > 50 || offset > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid user directory page request");
    }
    String search = keyword == null ? "" : keyword.trim();
    if (search.codePointCount(0, search.length()) > 320) {
      throw new IllegalArgumentException("User directory prefix is too long");
    }
    if (search.codePointCount(0, search.length()) < 3) {
      return new UserDirectoryPage(List.of(), 0);
    }
    String prefix =
        search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
            + "%";
    String where =
        " where u.deletedAt is null and u.enabled = true and "
            + "(lower(u.email) like :prefix escape '!' or u.phoneNumber like :prefix escape '!')";
    var content =
        entityManager
            .createQuery(
                PROJECTION + where + " order by " + UserDisplayName.HQL + ", u.id",
                UserDirectoryEntry.class)
            .setParameter("prefix", prefix)
            .setFirstResult((int) offset)
            .setMaxResults(size)
            .getResultList();
    long total =
        entityManager
            .createQuery("select count(u) from User u" + where, Long.class)
            .setParameter("prefix", prefix)
            .getSingleResult();
    return new UserDirectoryPage(content, total);
  }

  private static void requireServiceCaller() {
    if (ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .isEmpty()) {
      throw new AccessDeniedException("User directory requires an authenticated service client");
    }
  }
}
