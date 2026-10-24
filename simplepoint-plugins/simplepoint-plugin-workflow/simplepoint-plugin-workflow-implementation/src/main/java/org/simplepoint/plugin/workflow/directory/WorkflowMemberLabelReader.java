package org.simplepoint.plugin.workflow.directory;

import java.util.function.Supplier;

/** Executes an already authorized display query against one source-fenced local snapshot. */
public interface WorkflowMemberLabelReader {
  /** The supplier performs only local reads and returns detached values, not managed entities. */
  <T> T read(String tenant, Supplier<T> query);
}
