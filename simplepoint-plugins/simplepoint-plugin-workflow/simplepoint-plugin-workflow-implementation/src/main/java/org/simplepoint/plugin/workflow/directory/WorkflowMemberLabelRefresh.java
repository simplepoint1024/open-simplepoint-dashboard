package org.simplepoint.plugin.workflow.directory;

import org.springframework.stereotype.Service;

/** Bounded recovery and reconciliation; no queue or inbox transaction spans a directory RPC. */
@Service
public class WorkflowMemberLabelRefresh {
  private final WorkflowMemberLabelRefreshQueue queue;
  private final WorkflowMemberLabelProjection projection;
  private final WorkflowMemberLabelRefreshProperties properties;

  /** Both foreground and background work share the same generation/lease coordinator. */
  public WorkflowMemberLabelRefresh(
      WorkflowMemberLabelRefreshQueue queue,
      WorkflowMemberLabelProjection projection,
      WorkflowMemberLabelRefreshProperties properties) {
    this.queue = queue;
    this.projection = projection;
    this.properties = properties;
  }

  /** A failed tenant cannot starve all others; persisted backoff makes it yield its position. */
  public int runBatch() {
    int completed = 0;
    while (completed < properties.batchSize() && !Thread.currentThread().isInterrupted()) {
      var job = queue.claim();
      if (job == null) {
        break;
      }
      try {
        queue.finish(job, projection.advance(job.tenant()));
      } catch (RuntimeException failure) {
        queue.failed(job, failure);
      }
      completed++;
    }
    return completed;
  }
}
