package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceWatcherEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class WorkflowMatterQueriesTest extends WorkflowDirectoryFixture {
  private WorkflowMatterQueries matters;

  @BeforeEach
  void queries() {
    matters = new WorkflowMatterQueries(em);
  }

  @Test
  void startersCanBrowsePublishedOptionsWithoutDesignerPermissionButNeverDraftsOrOtherTenants() {
    String published = publish("${initiator}", null);
    transaction.execute(
        status ->
            service.createDefinition(
                new org.simplepoint.plugin.workflow.api.model.WorkflowModels
                    .CreateDefinitionCommand("draftOnly", "Secret draft", null, null)));
    transaction.executeWithoutResult(
        status -> {
          var current = service.definition(published);
          service.updateDraft(
              published,
              new org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand(
                  current.definition().draftRevision(),
                  "Unpublished next name",
                  "Secret category",
                  "Secret description",
                  current.bpmnXml(),
                  null));
        });
    authorize("actor", "tenant", false, List.of("workflow.instances.start"));
    var catalog = transaction.execute(status -> matters.startable(Map.of(), PageRequest.of(0, 20)));
    assertThat(catalog.content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(published);
              assertThat(row.latestVersion()).isEqualTo(1);
              assertThat(row.name()).isEqualTo("Approval");
              assertThat(row.category()).isNull();
              assertThat(row.description()).isNull();
            });
    var selected = transaction.execute(status -> matters.startableOptions(List.of(published)));
    assertThat(selected).hasSize(1);
    authorize("actor", "other-tenant", true, List.of());
    var hidden = transaction.execute(status -> matters.startableOptions(List.of(published)));
    assertThat(hidden).isEmpty();
  }

  @Test
  void starterAndCopiedListsArePersonalEvenForAdministrators() {
    var instance = start(publish("${initiator}", null), "Visible");
    assertThat(rows("started").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.starterName()).isEqualTo("张三");
              assertThat(row.canWithdraw()).isTrue();
            });
    transaction.executeWithoutResult(
        status -> {
          var watcher = new WorkflowInstanceWatcherEntity();
          watcher.setId(UUID.randomUUID().toString());
          watcher.setTenantId("tenant");
          watcher.setProcessInstanceId(instance.id());
          watcher.setUserId("reviewer");
          watcher.setCreatedAt(Instant.now());
          em.persist(watcher);
        });
    assertThat(rows("copied").content()).isEmpty();
    authorize("reviewer", "tenant", false, List.of("workflow.instances.started.view"));
    assertThat(rows("started").content()).isEmpty();
    assertThat(rows("copied").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.canWithdraw()).isFalse();
              assertThat(row.canCancel()).isFalse();
            });
    assertThatThrownBy(() -> rows("all")).isInstanceOf(WorkflowException.class);
    authorize("reviewer", "tenant", true, List.of());
    assertThat(rows("started").content()).isEmpty();
    assertThat(rows("all").content()).hasSize(1);
    authorize("reviewer", "other-tenant", true, List.of());
    assertThat(rows("all").content()).isEmpty();
    assertThat(rows("copied").content()).isEmpty();
  }

  @Test
  void endedEngineInstancesHaveNoStaleRunningActions() {
    var instance = start(publish("${initiator}", null), "Completed by engine");
    engine
        .getTaskService()
        .complete(
            engine
                .getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.id())
                .singleResult()
                .getId());
    assertThat(rows("started").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.status()).isEqualTo("COMPLETED");
              assertThat(row.endedAt()).isNotNull();
              assertThat(row.canWithdraw()).isFalse();
              assertThat(row.canCancel()).isFalse();
            });
  }

  @Test
  void ordinaryStarterCanWithdrawButCannotCancelWithoutTheSeparateAuthority() {
    var instance = start(publish("${initiator}", null), "Authority");
    authorize("actor", "tenant", false, List.of("workflow.instances.started.view"));
    assertThat(rows("started").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.canWithdraw()).isTrue();
              assertThat(row.canCancel()).isFalse();
            });
    transaction.execute(status -> service.withdraw(instance.id(), "Correction"));
    assertThat(rows("started").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.status()).isEqualTo("WITHDRAWN");
              assertThat(row.canWithdraw()).isFalse();
              assertThat(row.canCancel()).isFalse();
            });
  }

  @Test
  void filtersAndStableSortApplyBeforeCountAndPagingAndDoNotExposeOwnershipIds() {
    String definition = publish("${initiator}", null);
    for (String title : List.of("C", "A", "B")) {
      start(definition, title);
    }
    var page =
        transaction.execute(
            status ->
                matters.query(
                    "started",
                    Map.of("starterName", "equals:张三"),
                    PageRequest.of(1, 1, Sort.by("title"))));
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.content()).singleElement().extracting(WorkflowMatter::title).isEqualTo("B");
    assertThatThrownBy(
            () ->
                matters.query(
                    "started", Map.of("starterId", "equals:actor"), PageRequest.of(0, 20)))
        .isInstanceOf(InvalidQueryException.class);
    assertThat(matters.schema("started").toString())
        .contains("x-query", "starterName", "definitionVersion")
        .doesNotContain("tenantId", "starterId");
  }

  private ProcessInstanceSummary start(String definition, String title) {
    return transaction.execute(
        status ->
            service.start(definition, new StartProcessCommand(null, null, title, Map.of(), null)));
  }

  private PageResult<WorkflowMatter> rows(String view) {
    return transaction.execute(status -> matters.query(view, Map.of(), PageRequest.of(0, 20)));
  }
}
