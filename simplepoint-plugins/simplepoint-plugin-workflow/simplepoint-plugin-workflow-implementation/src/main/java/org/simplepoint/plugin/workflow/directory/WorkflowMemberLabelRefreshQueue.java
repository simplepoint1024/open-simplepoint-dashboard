package org.simplepoint.plugin.workflow.directory;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable refresh intent with its own fenced lease; it never calls the identity directory. */
@Service
public class WorkflowMemberLabelRefreshQueue {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final TransactionTemplate effect;
  private final WorkflowMemberLabelRefreshProperties properties;

  /** The event handler and queue share exactly the workflow storage binding. */
  public WorkflowMemberLabelRefreshQueue(
      DataSource source,
      PlatformTransactionManager manager,
      WorkflowMemberLabelRefreshProperties properties) {
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
    effect = new TransactionTemplate(manager);
    effect.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    this.properties = properties;
  }

  /**
   * Joins the inbox transaction; unknown projections are lazy, and older same-epoch hints are
   * ignored.
   */
  public void request(String tenant, Revision revision) {
    effect.executeWithoutResult(
        status ->
            jdbc.update(
                """
                update simpoint_workflow_member_label_refresh q
                set generation=generation+1,due_at=least(due_at,clock_timestamp())
                from simpoint_workflow_member_label_state s
                where q.tenant_id=s.tenant_id and q.tenant_id=?
                  and (s.observed_epoch is null or s.observed_epoch <> ? or s.observed_revision < ?)
                """,
                tenant,
                revision.epoch(),
                revision.sequence()));
  }

  /** Claims one due job without waiting for another worker, using the database clock. */
  public Job claim() {
    return transaction.execute(
        status -> {
          var rows =
              jdbc.query(
                  """
                  select tenant_id,generation,failures from simpoint_workflow_member_label_refresh
                  where due_at <= clock_timestamp() and (lease_until is null or lease_until <= clock_timestamp())
                  order by due_at,tenant_id limit 1 for update skip locked
                  """,
                  (row, index) ->
                      new Job(
                          row.getString(1),
                          row.getLong(2),
                          UUID.randomUUID().toString(),
                          row.getInt(3)));
          if (rows.isEmpty()) {
            return null;
          }
          var job = rows.getFirst();
          jdbc.update(
              """
              update simpoint_workflow_member_label_refresh
              set lease_token=?,lease_until=clock_timestamp()+(? * interval '1 millisecond') where tenant_id=?
              """,
              job.token(),
              properties.leaseDuration().toMillis(),
              job.tenant());
          return job;
        });
  }

  /**
   * Keeps newer notifications due and prevents an expired owner from releasing a successor's claim.
   */
  public void finish(Job job, WorkflowMemberLabelProjection.Result result) {
    boolean ready = result == WorkflowMemberLabelProjection.Result.READY;
    complete(job, ready ? properties.reconcileInterval() : properties.delay(), 0, null, ready);
  }

  /**
   * Retries outages with bounded backoff; only stable categories are retained, never exception
   * text.
   */
  public void failed(Job job, RuntimeException failure) {
    int failures = Math.min(20, job.failures() + 1);
    String category =
        failure instanceof WorkflowMemberLabelException label
            ? label.reason().name()
            : "UNAVAILABLE";
    complete(
        job,
        Duration.ofSeconds(Math.min(300, 2L << Math.min(8, failures - 1))),
        failures,
        category,
        false);
  }

  /** Low-cardinality operator metrics; tenant identities and source messages are never tags. */
  public long count(State state) {
    String condition =
        switch (state) {
          case DUE ->
              "due_at<=clock_timestamp() and (lease_until is null or"
                  + " lease_until<=clock_timestamp())";
          case LEASED -> "lease_until>clock_timestamp()";
          case FAILED -> "last_error is not null";
        };
    return jdbc.queryForObject(
        "select count(*) from simpoint_workflow_member_label_refresh where " + condition,
        Long.class);
  }

  /** Queue health dimensions independent of broker health and domain readiness. */
  public enum State {
    DUE,
    LEASED,
    FAILED
  }

  private void complete(Job job, Duration delay, int failures, String error, boolean checked) {
    transaction.executeWithoutResult(
        status ->
            jdbc.update(
                """
                update simpoint_workflow_member_label_refresh
                set due_at=case when generation=? then clock_timestamp()+(? * interval '1 millisecond')
                               else least(due_at,clock_timestamp()) end,
                    failures=case when generation=? then ? else failures end,
                    last_error=case when generation=? then ? else last_error end,
                    checked_at=case when ? then clock_timestamp() else checked_at end,
                    lease_token=null,lease_until=null
                where tenant_id=? and lease_token=?
                """,
                job.generation(),
                delay.toMillis(),
                job.generation(),
                failures,
                job.generation(),
                error,
                checked,
                job.tenant(),
                job.token()));
  }

  /** A snapshot of one attempt, not an authority to mutate another claim. */
  public record Job(String tenant, long generation, String token, int failures) {}
}
