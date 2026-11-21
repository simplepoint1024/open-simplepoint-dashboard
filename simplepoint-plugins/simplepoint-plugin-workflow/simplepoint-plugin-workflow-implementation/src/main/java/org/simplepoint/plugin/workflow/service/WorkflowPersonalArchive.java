package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.entity.WorkflowPersonalArchiveEntity;

/** Shared authorization and storage for the four participant-specific archives. */
final class WorkflowPersonalArchive {
  private WorkflowPersonalArchive() {}

  static Predicate visible(CriteriaBuilder cb, CommonAbstractCriteria query, Expression<String> id,
      String tenant, String user, String list, boolean archived) {
    var match = query.subquery(Integer.class);
    var archive = match.from(WorkflowPersonalArchiveEntity.class);
    match.select(cb.literal(1)).where(
        cb.equal(archive.get("tenantId"), tenant),
        cb.equal(archive.get("userId"), user),
        cb.equal(archive.get("listName"), list),
        cb.equal(archive.get("entryId"), id));
    var exists = cb.exists(match);
    return archived ? exists : cb.not(exists);
  }

  static <T> void change(EntityManager em, Class<T> type, String processField, String tenant,
      String user, String list, List<String> ids, boolean archive, ProjectionPages.Boundary<T> boundary) {
    if (ids == null || ids.isEmpty() || ids.size() > TablePagination.MAX_PAGE_SIZE
        || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)
        || ids.stream().distinct().count() != ids.size()) {
      throw new WorkflowException(WorkflowException.Code.VALIDATION, "Invalid personal list selection");
    }
    var cb = em.getCriteriaBuilder();
    var query = cb.createTupleQuery();
    var root = query.from(type);
    query.multiselect(root.get("id"), root.get(processField))
        .where(boundary.apply(cb, query, root), root.get("id").in(ids))
        .orderBy(cb.asc(root.get("id")));
    var entries = em.createQuery(query).getResultList();
    if (entries.size() != ids.size()) {
      throw new WorkflowException(WorkflowException.Code.NOT_FOUND, "Personal list selection is no longer available");
    }
    if (!archive) {
      em.createQuery("delete from WorkflowPersonalArchiveEntity a where a.tenantId = :tenant"
              + " and a.userId = :user and a.listName = :list and a.entryId in :ids")
          .setParameter("tenant", tenant).setParameter("user", user)
          .setParameter("list", list).setParameter("ids", ids).executeUpdate();
      return;
    }
    var now = Instant.now();
    var insert = em.createQuery("""
        insert into WorkflowPersonalArchiveEntity
          (id, tenantId, userId, listName, entryId, processInstanceId, archivedAt)
        values (:id, :tenant, :user, :list, :entry, :process, :archived)
        on conflict do nothing
        """).setParameter("tenant", tenant).setParameter("user", user)
        .setParameter("list", list).setParameter("archived", now);
    for (var entry : entries) {
      String entryId = entry.get(0, String.class);
      String key = String.join("\u0000", tenant, user, list, entryId);
      insert.setParameter("id", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString())
          .setParameter("entry", entryId)
          .setParameter("process", entry.get(1, String.class)).executeUpdate();
    }
  }
}
