package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.workflow.api.constants.WorkflowResourceCodes;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;

class WorkflowOverviewQueriesTest extends WorkflowDirectoryFixture {
  @Test
  void homeUsesFullCountsFiveRowPreviewsAndEngineAuthoritativeStatus() {
    var overview =
        new WorkflowOverviewQueries(new WorkflowInboxQueries(em), new WorkflowMatterQueries(em));
    String definition = publish("actor", null);
    for (int i = 0; i < 7; i++) {
      transaction.execute(
          status ->
              service.start(
                  definition, new StartProcessCommand(1, null, "Matter", Map.of(), List.of())));
    }
    var before = transaction.execute(status -> overview.overview());
    assertThat(before.pendingTodos()).isEqualTo(7);
    assertThat(before.activeMatters()).isEqualTo(7);
    assertThat(before.todos()).hasSize(5);
    assertThat(before.matters()).hasSize(5);
    transaction.execute(
        status ->
            service.complete(
                before.todos().getFirst().taskId(), new CompleteTaskCommand(Map.of(), "Approved")));
    var after = transaction.execute(status -> overview.overview());
    assertThat(after.pendingTodos()).isEqualTo(6);
    assertThat(after.activeMatters()).isEqualTo(6);
    authorize("reviewer", AuthorizationScopeType.TENANT, true, List.of());
    var other = transaction.execute(status -> overview.overview());
    assertThat(other.pendingTodos()).isZero();
    assertThat(other.activeMatters()).isZero();
  }

  @Test
  void cardsAreIndependentlyAuthorizedAndDoNotQueryHiddenData() {
    var inbox = mock(WorkflowInboxQueries.class);
    var matters = mock(WorkflowMatterQueries.class);
    var overview = new WorkflowOverviewQueries(inbox, matters);
    authorize("actor", AuthorizationScopeType.TENANT, false, List.of());
    var hidden = overview.overview();
    assertThat(hidden.tenantWorkspace()).isTrue();
    assertThat(hidden.canViewTodos()).isFalse();
    assertThat(hidden.canViewMatters()).isFalse();
    assertThat(hidden.todos()).isEmpty();
    assertThat(hidden.matters()).isEmpty();
    verifyNoInteractions(inbox, matters);
    authorize("actor", AuthorizationScopeType.PLATFORM, false, List.of());
    assertThat(overview.overview().tenantWorkspace()).isFalse();
    verifyNoInteractions(inbox, matters);
  }

  @Test
  void taskOnlyPermissionDoesNotExposeStarterRows() {
    var overview =
        new WorkflowOverviewQueries(new WorkflowInboxQueries(em), new WorkflowMatterQueries(em));
    transaction.execute(
        status ->
            service.start(
                publish("actor", null),
                new StartProcessCommand(1, null, "Matter", Map.of(), List.of())));
    authorize(
        "actor", AuthorizationScopeType.TENANT, false, List.of(WorkflowResourceCodes.TASKS_VIEW));
    var result = transaction.execute(status -> overview.overview());
    assertThat(result.canViewTodos()).isTrue();
    assertThat(result.pendingTodos()).isEqualTo(1);
    assertThat(result.canViewMatters()).isFalse();
    assertThat(result.matters()).isEmpty();
  }

  private void authorize(
      String user, AuthorizationScopeType scope, boolean admin, List<String> resources) {
    var context = new AuthorizationContext();
    context.setUserId(user);
    context.setScopeType(scope);
    context.setIsAdministrator(admin);
    context.setResources(resources);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
