package org.simplepoint.plugin.notification.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationStatus;
import org.simplepoint.plugin.notification.api.repository.NotificationInboxRepository;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.InboxItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** Database-side audience matching for notification inboxes. */
@Repository
public class JpaNotificationInboxRepository implements NotificationInboxRepository {

  private static final String VISIBLE = """
      n.deletedAt is null
      and n.status = :published
      and n.publishAt <= :now
      and (n.expireAt is null or n.expireAt > :now)
      and (
        n.audienceType = :allAudience
        or (n.audienceType = :platformAudience and :scopeType = :platformScope)
        or (n.audienceType = :tenantAudience
          and :scopeType = :tenantScope
          and n.audienceId = :tenantId)
        or (n.audienceType = :userAudience and n.audienceId = :userId)
      )
      """;

  private final EntityManager entityManager;

  /** Creates the optimized inbox repository. */
  public JpaNotificationInboxRepository(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Page<InboxItem> findVisible(
      final AudienceContext audience,
      final Instant now,
      final Pageable pageable
  ) {
    Query data = query(
        """
        select n, r.readAt
        from SystemNotification n
        left join NotificationReceipt r
          on r.notificationId = n.id
          and r.userId = :userId
          and r.deletedAt is null
        where""" + " " + VISIBLE
            + " order by case n.priority"
            + " when org.simplepoint.plugin.notification.api.model.NotificationPriority.URGENT"
            + " then 0"
            + " when org.simplepoint.plugin.notification.api.model.NotificationPriority.HIGH"
            + " then 1 else 2 end,"
            + " n.publishAt desc",
        audience,
        now
    );
    if (pageable.isPaged()) {
      data.setFirstResult(Math.toIntExact(pageable.getOffset()));
      data.setMaxResults(pageable.getPageSize());
    }
    List<InboxItem> content = rows(data).stream().map(this::toItem).toList();
    long total = number(query(
        "select count(n) from SystemNotification n where " + VISIBLE,
        audience,
        now
    ).getSingleResult());
    return new PageImpl<>(content, pageable, total);
  }

  @Override
  public long countUnread(final AudienceContext audience, final Instant now) {
    Query query = query(
        """
        select count(n)
        from SystemNotification n
        left join NotificationReceipt r
          on r.notificationId = n.id
          and r.userId = :userId
          and r.deletedAt is null
        where""" + " " + VISIBLE + " and r.id is null",
        audience,
        now
    );
    return number(query.getSingleResult());
  }

  @Override
  public boolean isVisible(
      final String notificationId,
      final AudienceContext audience,
      final Instant now
  ) {
    Query query = query(
        "select count(n) from SystemNotification n where " + VISIBLE
            + " and n.id = :notificationId",
        audience,
        now
    );
    query.setParameter("notificationId", notificationId);
    return number(query.getSingleResult()) > 0L;
  }

  @Override
  public List<String> findVisibleIds(
      final AudienceContext audience,
      final Instant now
  ) {
    @SuppressWarnings("unchecked")
    List<String> result = query(
        "select n.id from SystemNotification n where " + VISIBLE,
        audience,
        now
    ).getResultList();
    return result;
  }

  private Query query(
      final String jpql,
      final AudienceContext audience,
      final Instant now
  ) {
    return entityManager.createQuery(jpql)
        .setParameter("published", NotificationStatus.PUBLISHED)
        .setParameter("now", now)
        .setParameter("allAudience", NotificationAudienceType.ALL)
        .setParameter("platformAudience", NotificationAudienceType.PLATFORM)
        .setParameter("tenantAudience", NotificationAudienceType.TENANT)
        .setParameter("userAudience", NotificationAudienceType.USER)
        .setParameter("scopeType", audience.scopeType())
        .setParameter("platformScope", AuthorizationScopeType.PLATFORM)
        .setParameter("tenantScope", AuthorizationScopeType.TENANT)
        .setParameter("tenantId", audience.tenantId())
        .setParameter("userId", audience.userId());
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(final Query query) {
    return query.getResultList();
  }

  private InboxItem toItem(final Object[] row) {
    SystemNotification notification = (SystemNotification) row[0];
    Instant readAt = (Instant) row[1];
    return new InboxItem(
        notification.getId(),
        notification.getTitle(),
        notification.getContent(),
        notification.getCategory(),
        notification.getPriority(),
        notification.getLinkUrl(),
        notification.getPublishAt(),
        notification.getExpireAt(),
        readAt != null,
        readAt
    );
  }

  private static long number(final Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }
}
