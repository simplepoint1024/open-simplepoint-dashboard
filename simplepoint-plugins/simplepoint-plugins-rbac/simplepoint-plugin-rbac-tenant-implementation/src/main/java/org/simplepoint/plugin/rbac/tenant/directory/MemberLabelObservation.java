package org.simplepoint.plugin.rbac.tenant.directory;

import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Observes committed source versions; each tenant cursor and outbox fact commit atomically. */
public class MemberLabelObservation {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final EventPublisher publisher;
  private final MemberLabelObservationProperties properties;

  /**
   * All storage and the publisher are bound to the identity module, including in aggregate hosts.
   */
  public MemberLabelObservation(
      DataSource source,
      PlatformTransactionManager manager,
      EventPublisher publisher,
      MemberLabelObservationProperties properties) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
    this.publisher = publisher;
    this.properties = properties;
  }

  /**
   * A locked source transaction serializes observers without waiting on an in-flight source write.
   */
  public int observe() {
    return Objects.requireNonNull(
        transaction.execute(
            status -> {
              var source =
                  jdbc.query(
                      """
                      select epoch,revision,notification_epoch,notification_revision,notification_complete
                      from simpoint_identity_member_label_revision where singleton=true for update skip locked
                      """,
                      (row, index) ->
                          new Source(
                              new Revision(row.getString(1), row.getLong(2)),
                              row.getString(3) == null
                                  ? null
                                  : new Revision(row.getString(3), row.getLong(4)),
                              row.getBoolean(5)));
              if (source.isEmpty()) {
                // Missing revision is a broken installation, while an active writer is a normal
                // deferral.
                if (jdbc.queryForObject(
                        "select count(*) from simpoint_identity_member_label_revision",
                        Integer.class)
                    != 1) {
                  throw new IllegalStateException("Member label revision is missing");
                }
                return 0;
              }
              var revision = source.getFirst().revision();
              checkRegression(source.getFirst().notified(), revision);
              if (revision.equals(source.getFirst().notified()) && source.getFirst().complete()) {
                return 0;
              }
              jdbc.update(
                  """
                  update simpoint_identity_member_label_revision
                  set notification_epoch=?,notification_revision=?,notification_complete=false where singleton=true
                  """,
                  revision.epoch(),
                  revision.sequence());
              var tenants =
                  jdbc.query(
                      """
                      select tenant_id,observed_epoch,observed_revision
                      from simpoint_identity_member_label_observations
                      where observed_epoch is null or observed_epoch <> ? or observed_revision <> ?
                      order by observed_at nulls first,tenant_id limit ?
                      """,
                      (row, index) ->
                          new Tenant(
                              row.getString(1),
                              row.getString(2) == null
                                  ? null
                                  : new Revision(row.getString(2), row.getLong(3))),
                      revision.epoch(),
                      revision.sequence(),
                      properties.batchSize());
              for (var tenant : tenants) {
                checkRegression(tenant.observed(), revision);
                publisher.publish(MemberLabelEvents.observed(tenant.id(), revision, Instant.now()));
                jdbc.update(
                    """
                    update simpoint_identity_member_label_observations
                    set observed_epoch=?,observed_revision=?,observed_at=clock_timestamp() where tenant_id=?
                    """,
                    revision.epoch(),
                    revision.sequence(),
                    tenant.id());
              }
              if (tenants.size() < properties.batchSize()) {
                jdbc.update(
                    """
                    update simpoint_identity_member_label_revision
                    set notification_epoch=?,notification_revision=?,notification_complete=true where singleton=true
                    """,
                    revision.epoch(),
                    revision.sequence());
              }
              return tenants.size();
            }));
  }

  /**
   * Number of tenant observations still awaiting durable publication, without tenant metric tags.
   */
  public long pending() {
    return jdbc.queryForObject(
        """
        select count(*) from simpoint_identity_member_label_observations o
        cross join simpoint_identity_member_label_revision r
        where o.observed_epoch is null or o.observed_epoch <> r.epoch or o.observed_revision <> r.revision
        """,
        Long.class);
  }

  private static void checkRegression(Revision previous, Revision current) {
    if (previous != null
        && previous.epoch().equals(current.epoch())
        && previous.sequence() > current.sequence()) {
      throw new IllegalStateException("Member label source revision regressed");
    }
  }

  private record Source(Revision revision, Revision notified, boolean complete) {}

  private record Tenant(String id, Revision observed) {}
}
