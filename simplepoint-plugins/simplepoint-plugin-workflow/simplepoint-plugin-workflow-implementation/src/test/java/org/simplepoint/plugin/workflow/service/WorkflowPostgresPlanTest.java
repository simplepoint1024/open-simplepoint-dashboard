package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Subselect;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowInboxReadModel;
import org.springframework.data.domain.PageRequest;

/**
 * EXPLAIN uses native Flowable-created tables, seeded only in the isolated disposable test schema.
 */
class WorkflowPostgresPlanTest extends WorkflowDirectoryFixture {
  @Test
  void personalPendingAndCompletedPlansStayBoundedWithTwentyThousandUnrelatedTasks()
      throws Exception {
    Assumptions.assumeTrue(
        "1".equals(System.getenv("WORKFLOW_TEST_POSTGRES")), "Real PostgreSQL plan gate");
    String definition = publish("actor", null);
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(1, null, "Plan fixture", Map.of(), List.of())));
    // No engine-table write exists in production code. Synthetic unrelated rows make query-plan
    // regressions reproducible without executing twenty thousand business processes in every test.
    transaction.executeWithoutResult(
        status -> {
          em.createNativeQuery(
                  "insert into ACT_HI_TASKINST (ID_, PROC_INST_ID_, NAME_, ASSIGNEE_, TENANT_ID_,"
                      + " START_TIME_, END_TIME_, TASK_DEF_KEY_) select 'plan-task-'||n, :instance,"
                      + " 'Unrelated', 'different-user', 'tenant', now(), case when n%2=0 then"
                      + " now() else null end, 'review' from generate_series(1,20000) n")
              .setParameter("instance", instance.id())
              .executeUpdate();
          em.createNativeQuery(
                  "insert into ACT_RU_TASK (ID_, REV_, PROC_INST_ID_, NAME_, ASSIGNEE_, TENANT_ID_,"
                      + " CREATE_TIME_, SUSPENSION_STATE_) select 'plan-task-'||n, 1, :instance,"
                      + " 'Unrelated', 'different-user', 'tenant', now(), 1 from"
                      + " generate_series(1,20000) n where n%2=1")
              .setParameter("instance", instance.id())
              .executeUpdate();
          em.createNativeQuery("ANALYZE").executeUpdate();
        });
    var inbox = new WorkflowInboxQueries(em);
    assertThat(
            transaction
                .execute(status -> inbox.query("pending", Map.of(), PageRequest.of(0, 20)))
                .totalElements())
        .isEqualTo(1);
    assertThat(
            transaction
                .execute(status -> inbox.query("completed", Map.of(), PageRequest.of(0, 20)))
                .totalElements())
        .isZero();
    String projection = WorkflowInboxReadModel.class.getAnnotation(Subselect.class).value();
    String pending =
        "tenant_id='tenant' and active=true and handled_at is null and (assignee_id='actor' or"
            + " (assignee_id is null and exists(select 1 from ACT_RU_IDENTITYLINK l where"
            + " l.TASK_ID_=w.task_id and l.TYPE_='candidate' and (l.USER_ID_='actor' or"
            + " l.GROUP_ID_='tenant.approver'))))";
    String completed =
        "tenant_id='tenant' and handled_at is not null"
            + " and (assignee_id='actor' or decision_actor_id='actor')";
    var json = new ObjectMapper();
    for (String tab : List.of("pending", "completed")) {
      String boundary = tab.equals("pending") ? pending : completed;
      for (boolean count : List.of(false, true)) {
        String query =
            "select "
                + (count ? "count(*)" : "*")
                + " from ("
                + projection
                + ") w where "
                + boundary
                + (count
                    ? ""
                    : " order by "
                        + (tab.equals("pending") ? "created_at" : "handled_at")
                        + " desc,id limit 20");
        String plan =
            transaction.execute(
                status ->
                    String.valueOf(
                        em.createNativeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query)
                            .getSingleResult()));
        var document = json.readTree(plan).get(0);
        double elapsed = document.path("Execution Time").asDouble();
        assertThat(document.path("Plan").path("Actual Rows").asInt()).isLessThanOrEqualTo(20);
        // Broad regression budget, not a production latency SLA. Emit actual timings for review.
        assertThat(elapsed)
            .as(tab + (count ? " count" : " data") + " milliseconds")
            .isLessThan(2000);
        System.out.printf(
            "WORKFLOW_PLAN %s %s %.3fms %s%n", tab, count ? "count" : "data", elapsed, plan);
      }
    }
  }
}
