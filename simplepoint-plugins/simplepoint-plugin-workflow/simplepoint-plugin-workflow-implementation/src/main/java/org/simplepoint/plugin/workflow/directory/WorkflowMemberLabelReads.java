package org.simplepoint.plugin.workflow.directory;

import java.util.function.Supplier;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException.Reason;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProjection.Result;
import org.springframework.stereotype.Service;

/** Source calls surround, never run inside, the transaction used for filtering/counting/paging. */
@Service
public class WorkflowMemberLabelReads implements WorkflowMemberLabelReader {
  private final WorkflowMemberLabelStore store;
  private final WorkflowMemberLabelProjection projection;
  private final MemberLabelDirectory directory;

  /** All dependencies retain workflow ownership and use its bound service identity. */
  public WorkflowMemberLabelReads(
      WorkflowMemberLabelStore store,
      WorkflowMemberLabelProjection projection,
      MemberLabelDirectory directory) {
    this.store = store;
    this.projection = projection;
    this.directory = directory;
  }

  @Override
  public <T> T read(String tenant, Supplier<T> query) {
    new Request(tenant, null, null, 1);
    return store.outsideTransaction(() -> readOutsideTransaction(tenant, query));
  }

  private <T> T readOutsideTransaction(String tenant, Supplier<T> query) {
    store.track(tenant);
    for (int attempt = 0; attempt < 2; attempt++) {
      Revision before = revision();
      var active = store.active(tenant);
      if (active == null || !before.equals(active.revision())) {
        if (advance(tenant) != Result.READY) {
          throw new WorkflowMemberLabelException(Reason.NOT_READY);
        }
        before = revision();
      }
      final T value;
      try {
        value = store.snapshot(tenant, before, query);
      } catch (WorkflowMemberLabelException changed) {
        if (changed.reason() != Reason.NOT_READY) {
          throw changed;
        }
        continue;
      }
      var after = revision();
      if (before.equals(after)) {
        return value;
      }
      store.observeSource(tenant, after);
    }
    throw new WorkflowMemberLabelException(Reason.NOT_READY);
  }

  private Result advance(String tenant) {
    try {
      return projection.advance(tenant);
    } catch (WorkflowMemberLabelException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private Revision revision() {
    try {
      var revision = directory.revision();
      if (revision == null) {
        throw new WorkflowMemberLabelException(Reason.INVALID_RESPONSE);
      }
      return revision;
    } catch (WorkflowMemberLabelException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private static WorkflowMemberLabelException unavailable(RuntimeException cause) {
    var failure = new WorkflowMemberLabelException(Reason.SOURCE_UNAVAILABLE);
    failure.initCause(cause);
    return failure;
  }
}
