package org.simplepoint.plugin.workflow.directory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException.Reason;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short, owned transactions fence workers; network reads never hold a projection database lock. */
@Service
public class WorkflowMemberLabelStore {
  private static final String STATE = "simpoint_workflow_member_label_state";
  private static final String LABELS = "simpoint_workflow_member_labels";
  private static final String CLEAR_BUILD =
      "build_generation=null,build_epoch=null,build_revision=null,build_cursor=null,build_pages=0,"
          + "build_rows=0,build_complete=false,build_tenant_active=null,lease_owner=null,lease_until=null";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate write;
  private final TransactionTemplate read;
  private final TransactionTemplate outside;
  private final TransactionTemplate snapshot;

  /** Module composition supplies workflow storage and its transaction manager together. */
  public WorkflowMemberLabelStore(DataSource source, PlatformTransactionManager transactions) {
    jdbc = new JdbcTemplate(source);
    write = new TransactionTemplate(transactions);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    write.setTimeout(10);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setReadOnly(true);
    read.setTimeout(10);
    outside = new TransactionTemplate(transactions);
    outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
    snapshot = new TransactionTemplate(transactions);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setTimeout(10);
  }

  /**
   * Rejects caller transactions before remote I/O; suspending would retain their connection/locks.
   */
  public <T> T outsideTransaction(Supplier<T> action) {
    return outside.execute(status -> action.get());
  }

  /**
   * Registers local interest before contacting the source, so outages can recover in background.
   */
  public void track(String tenant) {
    write.executeWithoutResult(
        status ->
            jdbc.update(
                "insert into " + STATE + "(tenant_id) values (?) on conflict do nothing", tenant));
  }

  /** Claims a new or resumable generation, never replacing a currently leased worker. */
  public Claim acquire(String tenant, Revision revision, String owner, Duration lease) {
    return write.execute(
        status -> {
          jdbc.update(
              "insert into " + STATE + "(tenant_id) values (?) on conflict do nothing", tenant);
          var state = state(tenant, true);
          rejectRegression(state.activeRevision(), revision);
          rejectRegression(state.buildRevision(), revision);
          rejectRegression(state.observedRevision(), revision);
          observe(tenant, revision);
          if (revision.equals(state.activeRevision())) {
            return new Claim(ClaimStatus.READY, null);
          }
          if (state.leased()) {
            return new Claim(ClaimStatus.BUSY, null);
          }
          String generation = state.buildGeneration();
          if (!revision.equals(state.buildRevision())) {
            deleteGeneration(tenant, generation);
            generation = UUID.randomUUID().toString();
            jdbc.update(
                "update "
                    + STATE
                    + " set build_cursor=null,build_pages=0,build_rows=0,build_complete=false,"
                    + " build_tenant_active=null,lease_owner=null,lease_until=null,build_generation=?,build_epoch=?,build_revision=?"
                    + " where tenant_id=?",
                generation,
                revision.epoch(),
                revision.sequence(),
                tenant);
          }
          jdbc.update(
              "update "
                  + STATE
                  + " set lease_owner=?,lease_until=clock_timestamp()+ (? * interval '1"
                  + " millisecond'), updated_at=clock_timestamp() where tenant_id=?",
              owner,
              lease.toMillis(),
              tenant);
          return new Claim(ClaimStatus.ACQUIRED, build(state(tenant, false), owner));
        });
  }

  /**
   * Commits a full page and its cursor atomically, with duplicate IDs rejected by the owned key.
   */
  public Build append(Build expected, Page page, Duration lease, int pageLimit) {
    try {
      return write.execute(
          status -> {
            var state = owned(expected);
            if (state.complete()
                || state.pages() != expected.pages()
                || !Objects.equals(state.cursor(), expected.cursor())) {
              throw new WorkflowMemberLabelException(Reason.LEASE_LOST);
            }
            if (state.pages() >= pageLimit) {
              throw new WorkflowMemberLabelException(Reason.BUILD_LIMIT);
            }
            if (!expected.tenant().equals(page.tenantId())
                || !expected.revision().equals(page.revision())
                || page.restartRequired()
                || state.pages() > 0
                    && !Objects.equals(state.buildTenantActive(), page.tenantActive())
                || page.nextUserId() != null && page.nextUserId().equals(state.cursor())) {
              throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
            }
            jdbc.batchUpdate(
                "insert into " + LABELS + "(tenant_id,generation,user_id,name) values (?,?,?,?)",
                page.labels().stream()
                    .map(
                        label ->
                            new Object[] {
                              expected.tenant(), expected.generation(), label.userId(), label.name()
                            })
                    .toList());
            jdbc.update(
                "update "
                    + STATE
                    + " set build_cursor=?,build_pages=build_pages+1,build_rows=build_rows+?,"
                    + " build_complete=?,build_tenant_active=?,lease_until=clock_timestamp()+ (? *"
                    + " interval '1 millisecond'), updated_at=clock_timestamp() where tenant_id=?",
                page.nextUserId(),
                page.labels().size(),
                page.nextUserId() == null,
                page.tenantActive(),
                lease.toMillis(),
                expected.tenant());
            return build(state(expected.tenant(), false), expected.owner());
          });
    } catch (DataIntegrityViolationException exception) {
      var failure = new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
      failure.addSuppressed(exception);
      throw failure;
    }
  }

  /** Requires a complete, still-owned build and a fresh source fence from the caller. */
  public void activate(Build expected, Revision confirmed) {
    write.executeWithoutResult(
        status -> {
          var state = owned(expected);
          if (!state.complete() || !expected.revision().equals(confirmed)) {
            throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
          }
          rejectRegression(state.activeRevision(), confirmed);
          rejectRegression(state.observedRevision(), confirmed);
          if (!confirmed.equals(state.observedRevision())) {
            throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
          }
          jdbc.update(
              "update "
                  + STATE
                  + " set active_generation=build_generation,source_epoch=build_epoch,"
                  + " source_revision=build_revision,tenant_active=build_tenant_active,"
                  + CLEAR_BUILD
                  + ",updated_at=clock_timestamp() where tenant_id=?",
              expected.tenant());
          deleteGeneration(expected.tenant(), state.activeGeneration());
        });
  }

  /** Keeps committed pages resumable after a time budget, transport failure or process restart. */
  public void release(Build expected) {
    write.executeWithoutResult(
        status ->
            jdbc.update(
                "update "
                    + STATE
                    + " set lease_owner=null,lease_until=null,updated_at=clock_timestamp()"
                    + " where tenant_id=? and build_generation=? and lease_owner=?",
                expected.tenant(),
                expected.generation(),
                expected.owner()));
  }

  /** Discards only this worker's staging data. A newer owner/generation is never touched. */
  public void abandon(Build expected) {
    write.executeWithoutResult(
        status -> {
          var state = state(expected.tenant(), true);
          if (state != null
              && expected.generation().equals(state.buildGeneration())
              && expected.owner().equals(state.owner())) {
            deleteGeneration(expected.tenant(), expected.generation());
            jdbc.update(
                "update "
                    + STATE
                    + " set "
                    + CLEAR_BUILD
                    + ",updated_at=clock_timestamp() where tenant_id=?",
                expected.tenant());
          }
        });
  }

  /** Remembers a newer source fence even after discarding staging; rollback cannot erase it. */
  public void restart(Build expected, Revision observed) {
    write.executeWithoutResult(
        status -> {
          var state = owned(expected);
          rejectRegression(state.observedRevision(), observed);
          rejectRegression(expected.revision(), observed);
          observe(expected.tenant(), observed);
          deleteGeneration(expected.tenant(), expected.generation());
          jdbc.update(
              "update "
                  + STATE
                  + " set "
                  + CLEAR_BUILD
                  + ",updated_at=clock_timestamp() where tenant_id=?",
              expected.tenant());
        });
  }

  /** Exposes only complete-generation metadata; no claim of source freshness is made here. */
  public Active active(String tenant) {
    return read.execute(
        status -> {
          var state = state(tenant, false);
          return state == null || state.activeGeneration() == null
              ? null
              : new Active(
                  tenant, state.activeGeneration(), state.activeRevision(), state.tenantActive());
        });
  }

  /** Remembers a changed read fence even when the request cannot finish a new generation. */
  public void observeSource(String tenant, Revision revision) {
    write.executeWithoutResult(
        status -> {
          jdbc.update(
              "insert into " + STATE + "(tenant_id) values (?) on conflict do nothing", tenant);
          var state = state(tenant, true);
          rejectRegression(state.activeRevision(), revision);
          rejectRegression(state.buildRevision(), revision);
          rejectRegression(state.observedRevision(), revision);
          observe(tenant, revision);
        });
  }

  /** The pointer, joined names, predicates, count and records share one database snapshot. */
  public <T> T snapshot(String tenant, Revision expected, Supplier<T> query) {
    return snapshot.execute(
        status -> {
          var state = state(tenant, false);
          if (state == null || !expected.equals(state.activeRevision())) {
            throw new WorkflowMemberLabelException(Reason.NOT_READY);
          }
          rejectRegression(state.observedRevision(), expected);
          if (!expected.equals(state.observedRevision())) {
            throw new WorkflowMemberLabelException(Reason.NOT_READY);
          }
          return query.get();
        });
  }

  private State owned(Build expected) {
    var state = state(expected.tenant(), true);
    if (state == null
        || !expected.generation().equals(state.buildGeneration())
        || !expected.owner().equals(state.owner())
        || !state.leased()
        || !expected.revision().equals(state.buildRevision())) {
      throw new WorkflowMemberLabelException(Reason.LEASE_LOST);
    }
    return state;
  }

  private State state(String tenant, boolean lock) {
    List<State> rows =
        jdbc.query(
            "select *,coalesce(lease_until>clock_timestamp(),false) as leased from "
                + STATE
                + " where tenant_id=?"
                + (lock ? " for update" : ""),
            WorkflowMemberLabelStore::state,
            tenant);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static State state(ResultSet row, int index) throws SQLException {
    return new State(
        row.getString("tenant_id"),
        row.getString("active_generation"),
        revision(row, "source"),
        revision(row, "observed"),
        row.getBoolean("tenant_active"),
        row.getString("build_generation"),
        revision(row, "build"),
        row.getString("build_cursor"),
        row.getInt("build_pages"),
        row.getLong("build_rows"),
        row.getBoolean("build_complete"),
        (Boolean) row.getObject("build_tenant_active"),
        row.getString("lease_owner"),
        row.getBoolean("leased"));
  }

  private static Revision revision(ResultSet row, String prefix) throws SQLException {
    var epoch = row.getString(prefix + "_epoch");
    return epoch == null ? null : new Revision(epoch, row.getLong(prefix + "_revision"));
  }

  private static Build build(State state, String owner) {
    return new Build(
        state.tenant(),
        state.buildGeneration(),
        owner,
        state.buildRevision(),
        state.cursor(),
        state.pages(),
        state.rows(),
        state.complete());
  }

  private static void rejectRegression(Revision current, Revision requested) {
    if (current != null
        && current.epoch().equals(requested.epoch())
        && current.sequence() > requested.sequence()) {
      throw new WorkflowMemberLabelException(Reason.SOURCE_REGRESSION);
    }
  }

  private void deleteGeneration(String tenant, String generation) {
    if (generation != null) {
      jdbc.update(
          "delete from " + LABELS + " where tenant_id=? and generation=?", tenant, generation);
    }
  }

  private void observe(String tenant, Revision revision) {
    jdbc.update(
        "update " + STATE + " set observed_epoch=?,observed_revision=? where tenant_id=?",
        revision.epoch(),
        revision.sequence(),
        tenant);
  }

  /** Claim outcomes are independent of the readiness of any business resource. */
  public enum ClaimStatus {
    READY,
    BUSY,
    ACQUIRED
  }

  /** Only ACQUIRED carries a mutable generation lease. */
  public record Claim(ClaimStatus status, Build build) {}

  /** Complete generation metadata; query callers must independently fence source freshness. */
  public record Active(String tenant, String generation, Revision revision, boolean tenantActive) {}

  /** Immutable worker token; store operations check the durable lease and cursor again. */
  public record Build(
      String tenant,
      String generation,
      String owner,
      Revision revision,
      String cursor,
      int pages,
      long rows,
      boolean complete) {}

  private record State(
      String tenant,
      String activeGeneration,
      Revision activeRevision,
      Revision observedRevision,
      boolean tenantActive,
      String buildGeneration,
      Revision buildRevision,
      String cursor,
      int pages,
      long rows,
      boolean complete,
      Boolean buildTenantActive,
      String owner,
      boolean leased) {}
}
