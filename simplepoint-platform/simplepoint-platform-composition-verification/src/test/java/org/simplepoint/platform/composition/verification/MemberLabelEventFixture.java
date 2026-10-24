package org.simplepoint.platform.composition.verification;

import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservation;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservationProperties;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelInvalidation;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefresh;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefreshProperties;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefreshQueue;
import org.springframework.transaction.PlatformTransactionManager;

/** Real owner-bound publishers, queue and handlers over the installed PostgreSQL event ledgers. */
final class MemberLabelEventFixture implements AutoCloseable {
  final MemberLabelProjectionFixture labels;
  final JdbcEventStore sender;
  final JdbcEventStore receiver;
  final MemberLabelObservation observation;
  final WorkflowMemberLabelRefreshQueue queue;
  final WorkflowMemberLabelInvalidation handler;
  final WorkflowMemberLabelRefreshProperties refreshProperties =
      new WorkflowMemberLabelRefreshProperties(
          2, Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofSeconds(60));
  final DataSource identitySource;
  final PlatformTransactionManager identityTransactions;

  MemberLabelEventFixture(boolean split, int members) {
    labels = new MemberLabelProjectionFixture(split, members);
    try {
      var bindings = labels.context.getBean(ModuleStorageBindings.class);
      var identity = bindings.require("identity");
      final var workflow = bindings.require("workflow");
      identitySource = labels.context.getBean(identity.dataSource(), DataSource.class);
      identityTransactions =
          labels.context.getBean(identity.transactionManager(), PlatformTransactionManager.class);
      sender = store(identitySource, identityTransactions, "common");
      receiver =
          store(
              labels.context.getBean(workflow.dataSource(), DataSource.class),
              labels.workflowTransactions,
              "workflow");
      labels.context.registerBean(EventPublisher.class, () -> sender);
      labels.context.registerBean(
          MemberLabelObservationProperties.class,
          () -> new MemberLabelObservationProperties(2, Duration.ofSeconds(2)));
      labels.context.registerBean(
          WorkflowMemberLabelRefreshProperties.class, () -> refreshProperties);
      labels.context.registerBean(MemberLabelObservation.class);
      labels.context.registerBean(WorkflowMemberLabelRefreshQueue.class);
      labels.context.registerBean(WorkflowMemberLabelInvalidation.class);
      observation = labels.context.getBean(MemberLabelObservation.class);
      queue = labels.context.getBean(WorkflowMemberLabelRefreshQueue.class);
      handler = labels.context.getBean(WorkflowMemberLabelInvalidation.class);
    } catch (RuntimeException | Error failure) {
      labels.close();
      throw failure;
    }
  }

  WorkflowMemberLabelRefresh refresh(int pagesPerRun) {
    return new WorkflowMemberLabelRefresh(
        queue, labels.projection(labels.directory, pagesPerRun, 10), refreshProperties);
  }

  void due() {
    labels.jdbc.update(
        "update simpoint_workflow_member_label_refresh set due_at=clock_timestamp()-interval '1"
            + " second'");
  }

  static JdbcEventStore store(
      DataSource source, PlatformTransactionManager transactions, String owner) {
    return new JdbcEventStore(
        source,
        transactions,
        new EventCodec(),
        owner,
        Clock.systemUTC(),
        EventRetryPolicy.defaults());
  }

  @Override
  public void close() {
    labels.close();
  }
}
