package org.simplepoint.plugin.workflow.directory;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException.Reason;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelStore.Build;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelStore.ClaimStatus;
import org.springframework.stereotype.Service;

/**
 * Resumable bounded rebuilds; both scheduled invalidations and query preparation reuse this owner.
 */
@Service
public class WorkflowMemberLabelProjection {
  private final WorkflowMemberLabelStore store;
  private final MemberLabelDirectory directory;
  private final WorkflowMemberLabelProperties limits;
  private final Semaphore workers;

  /** Uses the framework-bound workflow caller and owned store; no caller identity in payloads. */
  public WorkflowMemberLabelProjection(
      WorkflowMemberLabelStore store,
      MemberLabelDirectory directory,
      WorkflowMemberLabelProperties limits) {
    this.store = store;
    this.directory = directory;
    this.limits = limits;
    workers = new Semaphore(limits.concurrentRuns());
  }

  /** One bounded work slice. READY means a complete generation, not an authorization decision. */
  public Result advance(String tenant) {
    new Request(tenant, null, null, MemberLabelDirectoryModels.MAX_PAGE_SIZE);
    return store.outsideTransaction(() -> advanceOutsideTransaction(tenant));
  }

  private Result advanceOutsideTransaction(String tenant) {
    if (!workers.tryAcquire()) {
      return Result.BUSY;
    }
    try {
      long started = System.nanoTime();
      var revision = revision();
      var claim =
          store.acquire(tenant, revision, UUID.randomUUID().toString(), limits.leaseDuration());
      if (claim.status() == ClaimStatus.READY) {
        return Result.READY;
      }
      if (claim.status() == ClaimStatus.BUSY) {
        return Result.BUSY;
      }
      Build original = claim.build();
      Result result;
      try {
        result = build(original, started);
      } catch (RuntimeException | Error failure) {
        try {
          if (failure instanceof WorkflowMemberLabelException rejected
              && (rejected.reason() == Reason.INVALID_RESPONSE
                  || rejected.reason() == Reason.BUILD_LIMIT)) {
            store.abandon(original);
          } else {
            store.release(original);
          }
        } catch (RuntimeException | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
      store.release(original);
      return result;
    } finally {
      workers.release();
    }
  }

  private Result build(Build initial, long started) {
    Build build = initial;
    int fetched = 0;
    while (!build.complete()) {
      if (fetched >= limits.pagesPerRun() || elapsed(started)) {
        return Result.IN_PROGRESS;
      }
      if (build.pages() >= limits.pagesPerBuild()) {
        throw new WorkflowMemberLabelException(Reason.BUILD_LIMIT);
      }
      Page page =
          directory.page(
              new Request(
                  build.tenant(),
                  build.revision(),
                  build.cursor(),
                  MemberLabelDirectoryModels.MAX_PAGE_SIZE));
      if (page == null || !build.tenant().equals(page.tenantId())) {
        throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
      }
      if (page.restartRequired()) {
        if (build.revision().equals(page.revision())) {
          throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
        }
        store.restart(build, page.revision());
        return Result.RESTART_REQUIRED;
      }
      if (!build.revision().equals(page.revision())
          || page.nextUserId() != null
              && page.labels().size() != MemberLabelDirectoryModels.MAX_PAGE_SIZE) {
        throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
      }
      build = store.append(build, page, limits.leaseDuration(), limits.pagesPerBuild());
      fetched++;
    }
    if (elapsed(started)) {
      return Result.IN_PROGRESS;
    }
    Revision confirmed = revision();
    if (!build.revision().equals(confirmed)) {
      store.restart(build, confirmed);
      return Result.RESTART_REQUIRED;
    }
    store.activate(build, confirmed);
    return Result.READY;
  }

  private boolean elapsed(long started) {
    return System.nanoTime() - started >= limits.runTimeout().toNanos();
  }

  private Revision revision() {
    Revision revision = directory.revision();
    if (revision == null) {
      throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
    }
    return revision;
  }

  /** Outcomes let callers reschedule bounded work without treating partial data as ready. */
  public enum Result {
    READY,
    IN_PROGRESS,
    BUSY,
    RESTART_REQUIRED
  }
}
