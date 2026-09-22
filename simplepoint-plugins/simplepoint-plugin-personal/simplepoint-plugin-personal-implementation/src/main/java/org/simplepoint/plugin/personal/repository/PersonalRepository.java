package org.simplepoint.plugin.personal.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.plugin.personal.api.entity.OwnedRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** All reads include immutable owner/workspace predicates, including counts and history. */
@Repository
public class PersonalRepository {
  private final EntityManager entityManager;

  /** Uses the transaction-bound entity manager shared by platform JPA. */
  public PersonalRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Explicit ownership and bounded query criteria applied to every repository read. */
  public record Filter(
      String owner,
      String workspace,
      Map<String, Object> equal,
      String keyword,
      boolean trash,
      boolean overdue,
      Instant now,
      Map<String, String> columns) {
    /** Internal queries without user-selected column filters. */
    public Filter(
        String owner,
        String workspace,
        Map<String, Object> equal,
        String keyword,
        boolean trash,
        boolean overdue,
        Instant now) {
      this(owner, workspace, equal, keyword, trash, overdue, now, Map.of());
    }
  }

  private <T extends OwnedRecord> Predicate where(CriteriaBuilder cb, Root<T> root, Filter filter) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(PersonalQuery.filters(cb, root, filter.columns()));
    predicates.add(cb.equal(root.get("ownerId"), filter.owner()));
    predicates.add(cb.equal(root.get("workspace"), filter.workspace()));
    predicates.add(
        filter.trash() ? cb.isNotNull(root.get("deletedAt")) : cb.isNull(root.get("deletedAt")));
    filter
        .equal()
        .forEach(
            (key, value) -> {
              if (value instanceof List<?> values) {
                predicates.add(root.get(key).in(values));
              } else if (value == null) {
                predicates.add(cb.isNull(root.get(key)));
              } else {
                predicates.add(cb.equal(root.get(key), value));
              }
            });
    if (filter.keyword() != null && !filter.keyword().isBlank()) {
      String escaped =
          filter
              .keyword()
              .toLowerCase(java.util.Locale.ROOT)
              .replace("\\", "\\\\")
              .replace("%", "\\%")
              .replace("_", "\\_");
      predicates.add(cb.like(cb.lower(root.get("title")), "%" + escaped + "%", '\\'));
    }
    if (filter.overdue()) {
      predicates.add(cb.lessThan(root.get("dueAt"), filter.now()));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  /** Returns bounded rows and a count with identical ownership and filter predicates. */
  public <T extends OwnedRecord> Page<T> page(
      Class<T> type, Filter filter, Pageable page, String order) throws IllegalArgumentException {
    var cb = entityManager.getCriteriaBuilder();
    var query = cb.createQuery(type);
    var root = query.from(type);
    query.where(where(cb, root, filter));
    if (page.getSort().isSorted()) {
      query.orderBy(PersonalQuery.orders(cb, root, page.getSort()));
    } else if ("dueAt".equals(order)) {
      query.orderBy(
          cb.asc(cb.selectCase().when(cb.isNull(root.get("dueAt")), 1).otherwise(0)),
          cb.asc(root.get("dueAt")),
          cb.desc(root.get("createdAt")),
          cb.asc(root.get("id")));
    } else if ("items".equals(order)) {
      query.orderBy(
          cb.desc(root.get("pinned")),
          cb.desc(root.get("priority")),
          cb.desc(root.get("updatedAt")),
          cb.asc(root.get("id")));
    } else {
      query.orderBy(cb.desc(root.get("createdAt")), cb.asc(root.get("id")));
    }
    List<T> content =
        entityManager
            .createQuery(query)
            .setFirstResult(Math.toIntExact(page.getOffset()))
            .setMaxResults(page.getPageSize())
            .getResultList();
    return new PageImpl<>(content, page, count(type, filter));
  }

  /** Counts matching records without loading entities. */
  public <T extends OwnedRecord> long count(Class<T> type, Filter filter)
      throws IllegalArgumentException {
    var cb = entityManager.getCriteriaBuilder();
    var query = cb.createQuery(Long.class);
    var root = query.from(type);
    return entityManager
        .createQuery(query.select(cb.count(root)).where(where(cb, root, filter)))
        .getSingleResult();
  }

  /** Finds at most one record, preserving ownership predicates. */
  public <T extends OwnedRecord> Optional<T> find(Class<T> type, Filter filter) {
    var cb = entityManager.getCriteriaBuilder();
    var query = cb.createQuery(type);
    var root = query.from(type);
    return entityManager
        .createQuery(query.where(where(cb, root, filter)))
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  /** Persists a new entity whose ownership was assigned by the service. */
  public <T extends OwnedRecord> T insert(T entity) {
    entityManager.persist(entity);
    return entity;
  }

  /** Flushes versions and constraints before returning response projections. */
  public void flush() {
    entityManager.flush();
  }

  /** Single indexed bulk update, bounded to the current user and workspace. */
  public int readAllMessages(String owner, String workspace, Instant now) {
    return entityManager
        .createQuery(
            "update PersonalMessage set readAt = :now, updatedAt = :now, version = version + 1"
                + " where ownerId = :owner and workspace = :workspace and deletedAt is null and"
                + " readAt is null and archived = false")
        .setParameter("now", now)
        .setParameter("owner", owner)
        .setParameter("workspace", workspace)
        .executeUpdate();
  }
}
