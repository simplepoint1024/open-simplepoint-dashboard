package org.simplepoint.plugin.workflow.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Appends only: never takes a platform instance lock while Flowable owns engine locks. */
@Component
@DependsOnDatabaseInitialization
public class WorkflowEngineSignalListener implements FlowableEventListener {
  private static final List<FlowableEngineEventType> TYPES =
      List.of(
          FlowableEngineEventType.TASK_CREATED,
          FlowableEngineEventType.TASK_ASSIGNED,
          FlowableEngineEventType.PROCESS_COMPLETED,
          FlowableEngineEventType.PROCESS_CANCELLED,
          FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,
          FlowableEngineEventType.PROCESS_COMPLETED_WITH_ERROR_END_EVENT,
          FlowableEngineEventType.PROCESS_COMPLETED_WITH_ESCALATION_END_EVENT);
  private final JdbcTemplate jdbc;
  private final ObjectProvider<WorkflowCenterServiceImpl> workflow;

  /** Creates a transactional signal writer with lazy assignment validation. */
  public WorkflowEngineSignalListener(
      DataSource source, ObjectProvider<WorkflowCenterServiceImpl> workflow) {
    jdbc = new JdbcTemplate(source);
    this.workflow = workflow;
  }

  @Override
  public void onEvent(FlowableEvent event) {
    if (!TYPES.contains(event.getType())
        || !(event instanceof FlowableEngineEvent engineEvent)
        || engineEvent.getProcessInstanceId() == null) {
      return;
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Workflow engine signals require the engine transaction");
    }
    Timestamp now = Timestamp.from(Instant.now());
    // JdbcTemplate joins Spring's engine transaction. Rollback must remove this signal too.
    jdbc.update(
        "insert into simpoint_workflow_engine_signals"
            + " (id,process_instance_id,event_type,created_at,next_attempt_at,attempts) values"
            + " (?,?,?,?,?,0)",
        UUID.randomUUID().toString(),
        engineEvent.getProcessInstanceId(),
        event.getType().name(),
        now,
        now);
    if (event.getType() == FlowableEngineEventType.TASK_CREATED
        || event.getType() == FlowableEngineEventType.TASK_ASSIGNED) {
      var validation =
          TransactionSynchronizationManager.getSynchronizations().stream()
              .filter(AssignmentValidation.class::isInstance)
              .map(AssignmentValidation.class::cast)
              .filter(item -> item.owner() == this)
              .findFirst()
              .orElseGet(
                  () -> {
                    var item = new AssignmentValidation();
                    TransactionSynchronizationManager.registerSynchronization(item);
                    return item;
                  });
      validation.instances.add(engineEvent.getProcessInstanceId());
    }
  }

  private final class AssignmentValidation implements TransactionSynchronization {
    private final Set<String> instances = new LinkedHashSet<>();

    private WorkflowEngineSignalListener owner() {
      return WorkflowEngineSignalListener.this;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      // Candidate links are complete only after the command finishes. No platform lock/write:
      // an invalid background assignment rolls back the engine job and its wake-up signal.
      instances.forEach(id -> workflow.getObject().validateEngineAssignments(id));
    }
  }

  @Override
  public Collection<FlowableEngineEventType> getTypes() {
    return TYPES;
  }

  @Override
  public boolean isFailOnException() {
    return true;
  }

  @Override
  public boolean isFireOnTransactionLifecycleEvent() {
    return false;
  }

  @Override
  public String getOnTransaction() {
    return null;
  }
}
