package org.simplepoint.workflow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.service.WorkflowInboxQueries;
import org.simplepoint.plugin.workflow.service.WorkflowMatterQueries;
import org.simplepoint.plugin.workflow.service.WorkflowOverviewQueries;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Real Spring MVC binding/serialization and method-security proxies; domain/SQL boundaries have
 * separate engine tests.
 */
class WorkflowPersonalHttpTest {
  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void inboxCarriesTypedHeaderDocumentsAndRepeatedSortWithoutLosingPageOrView() throws Exception {
    var inbox = mock(WorkflowInboxQueries.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowInboxController(inbox))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    when(inbox.query(anyString(), anyMap(), any()))
        .thenReturn(new PageResult<>(List.of(), 2, 3, 12));
    when(inbox.schema())
        .thenReturn(
            Map.of("schema", Map.of("x-query", Map.of("version", 1)), "buttons", List.of()));
    String condition =
        "{\"version\":1,\"where\":{\"field\":\"starterName\",\"op\":\"like\",\"value\":\"张三\"}}";
    for (String tab : List.of("pending", "completed")) {
      mvc.perform(
              get("/inbox/" + tab)
                  .param("starterName", condition)
                  .param("page", "2")
                  .param("size", "3")
                  .param("sort", "handledAt,desc", "title,asc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(12));
      var page = ArgumentCaptor.forClass(Pageable.class);
      ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.captor();
      verify(inbox).query(eq(tab), filters.capture(), page.capture());
      assertThat(filters.getValue()).containsEntry("starterName", condition);
      assertThat(page.getValue())
          .isEqualTo(
              PageRequest.of(2, 3, Sort.by(Sort.Order.desc("handledAt"), Sort.Order.asc("title"))));
      mvc.perform(get("/inbox/" + tab + "/schema"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.schema.x-query.version").value(1));
    }
    mvc.perform(get("/inbox/tasks/task-1")).andExpect(status().isOk());
    verify(inbox).task("task-1");
    mvc.perform(get("/inbox/arbitrary-view")).andExpect(status().isNotFound());
  }

  @Test
  void matterViewsAndBoundedPickerKeepTheirDistinctTransportContracts() throws Exception {
    var matters = mock(WorkflowMatterQueries.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowMatterController(matters))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    for (String view : List.of("started", "copied", "all")) {
      mvc.perform(
              get("/matters/" + view)
                  .param("status", "in:REJECTED,WITHDRAWN")
                  .param("sort", "startedAt,desc"))
          .andExpect(status().isOk());
      verify(matters)
          .query(eq(view), anyMap(), eq(PageRequest.of(0, 20, Sort.by("startedAt").descending())));
      mvc.perform(get("/matters/" + view + "/schema")).andExpect(status().isOk());
      verify(matters).schema(view);
    }
    mvc.perform(get("/matters/startable").param("name", "like:报销").param("size", "20"))
        .andExpect(status().isOk());
    verify(matters).startable(anyMap(), eq(PageRequest.of(0, 20)));
    mvc.perform(get("/matters/startable/options").param("ids", "a,b")).andExpect(status().isOk());
    verify(matters).startableOptions(List.of("a", "b"));
    mvc.perform(get("/matters/foreign/schema")).andExpect(status().isNotFound());
  }

  @Test
  void lifecycleBodiesPreserveReasonsBooleansAndVersionPinsAndRejectMalformedRequests()
      throws Exception {
    var service = mock(WorkflowCenterService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowRuntimeController(service))
            .setControllerAdvice(new WorkflowExceptionHandler())
            .build();
    mvc.perform(
            post("/tasks/task-1/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":{\"approved\":false},\"comment\":\"已核实\"}"))
        .andExpect(status().isOk());
    var complete = ArgumentCaptor.forClass(CompleteTaskCommand.class);
    verify(service).complete(eq("task-1"), complete.capture());
    assertThat(complete.getValue().variables()).containsEntry("approved", false);
    assertThat(complete.getValue().comment()).isEqualTo("已核实");
    for (String action : List.of("reject", "withdraw", "cancel")) {
      String path = action.equals("reject") ? "/tasks/task-1/" : "/instances/instance-1/";
      mvc.perform(
              post(path + action)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"原始处理原因\"}"))
          .andExpect(status().isOk());
    }
    verify(service).reject("task-1", "原始处理原因");
    verify(service).withdraw("instance-1", "原始处理原因");
    verify(service).cancel("instance-1", "原始处理原因");
    mvc.perform(
            post("/instances/original/resubmit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":7,\"title\":\"修订申请\",\"variables\":{\"amount\":12},\"ccUserIds\":[\"watcher\"]}"))
        .andExpect(status().isOk());
    var start = ArgumentCaptor.forClass(StartProcessCommand.class);
    verify(service).resubmit(eq("original"), start.capture());
    assertThat(start.getValue().version()).isEqualTo(7);
    assertThat(start.getValue().ccUserIds()).containsExactly("watcher");
    clearInvocations(service);
    for (String path :
        List.of(
            "/tasks/task-1/reject",
            "/instances/instance-1/withdraw",
            "/instances/instance-1/cancel",
            "/instances/original/resubmit")) {
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @Test
  void progressNodeAndJournalReadsArePaginatedAndDomainConflictsKeepStableHttpErrors()
      throws Exception {
    var service = mock(WorkflowCenterService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowRuntimeController(service))
            .setControllerAdvice(new WorkflowExceptionHandler())
            .build();
    mvc.perform(get("/instances/instance-1/progress")).andExpect(status().isOk());
    verify(service).progress("instance-1");
    mvc.perform(
            get("/instances/instance-1/nodes/review")
                .param("page", "2")
                .param("size", "20")
                .param("activeOnly", "true"))
        .andExpect(status().isOk());
    verify(service).nodeHistory("instance-1", "review", 2, 20, true);
    mvc.perform(get("/instances/instance-1/actions").param("page", "3").param("size", "20"))
        .andExpect(status().isOk());
    verify(service).auditTrail("instance-1", 3, 20);
    mvc.perform(get("/instances/original/resubmission-form")).andExpect(status().isOk());
    verify(service).resubmissionForm("original");
    mvc.perform(get("/instances/instance-1/nodes/review").param("activeOnly", "invalid"))
        .andExpect(status().isBadRequest());
    when(service.reject(eq("finished"), anyString()))
        .thenThrow(new WorkflowException(WorkflowException.Code.CONFLICT, "任务已被处理"));
    mvc.perform(
            post("/tasks/finished/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"退回\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW_CONFLICT"));
  }

  @Test
  void genuineMethodSecurityDoesNotTurnReadAccessIntoApprovalOrCancellation() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var runtime = context.getBean(WorkflowRuntimeController.class);
      var inbox = context.getBean(WorkflowInboxController.class);
      var matters = context.getBean(WorkflowMatterController.class);
      var overview = context.getBean(WorkflowOverviewController.class);
      record Boundary(String authority, Runnable invoke) {}
      var reason = new WorkflowRuntimeController.CancelCommand("原因");
      var boundaries =
          List.of(
              new Boundary("workflow.tasks.view", inbox::schema),
              new Boundary(
                  "workflow.tasks.view",
                  () -> inbox.list("pending", Map.of(), PageRequest.of(0, 20))),
              new Boundary("workflow.tasks.view", () -> inbox.task("task")),
              new Boundary("workflow.instances.started.view", () -> matters.schema("started")),
              new Boundary(
                  "workflow.instances.started.view",
                  () -> matters.list("started", Map.of(), PageRequest.of(0, 20))),
              new Boundary(
                  "workflow.instances.start",
                  () -> matters.startable(Map.of(), PageRequest.of(0, 20))),
              new Boundary(
                  "workflow.instances.start",
                  () -> matters.startableOptions(List.of("definition"))),
              new Boundary("workflow.tasks.claim", () -> runtime.claim("task")),
              new Boundary("workflow.tasks.complete", () -> runtime.taskForm("task")),
              new Boundary("workflow.tasks.complete", () -> runtime.complete("task", null)),
              new Boundary("workflow.tasks.complete", () -> runtime.reject("task", reason)),
              new Boundary(
                  "workflow.instances.started.view", () -> runtime.withdraw("instance", reason)),
              new Boundary("workflow.instances.cancel", () -> runtime.cancel("instance", reason)),
              new Boundary("workflow.instances.start", () -> runtime.resubmissionForm("instance")),
              new Boundary("workflow.instances.start", () -> runtime.resubmit("instance", null)),
              new Boundary("workflow.tasks.view", () -> runtime.progress("instance")),
              new Boundary(
                  "workflow.tasks.view",
                  () -> runtime.nodeHistory("instance", "review", 0, 20, false)),
              new Boundary("workflow.tasks.view", () -> runtime.actions("instance", 0, 20)));
      for (var boundary : boundaries) {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(boundary.invoke()::run)
            .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        authenticate("workflow.definitions.view");
        assertThatThrownBy(boundary.invoke()::run).isInstanceOf(AccessDeniedException.class);
        authenticate(boundary.authority());
        boundary.invoke().run();
        authenticate("ROLE_Administrator");
        boundary.invoke().run();
      }
      authenticate("workflow.tasks.view");
      assertThatThrownBy(() -> runtime.complete("task", null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> runtime.reject("task", reason))
          .isInstanceOf(AccessDeniedException.class);
      authenticate("workflow.tasks.complete");
      assertThatThrownBy(() -> runtime.cancel("instance", reason))
          .isInstanceOf(AccessDeniedException.class);
      authenticate("workflow.instances.started.view");
      assertThatThrownBy(() -> runtime.cancel("instance", reason))
          .isInstanceOf(AccessDeniedException.class);
      SecurityContextHolder.clearContext();
      assertThatThrownBy(overview::overview)
          .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
      authenticate("unrelated.read");
      overview.overview(); // Per-card/domain scope is enforced by the overview query.
    }
  }

  private static void authenticate(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority(authority))));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class SecurityConfiguration {
    @Bean
    WorkflowRuntimeController runtime() {
      return new WorkflowRuntimeController(mock(WorkflowCenterService.class));
    }

    @Bean
    WorkflowInboxController inbox() {
      return new WorkflowInboxController(mock(WorkflowInboxQueries.class));
    }

    @Bean
    WorkflowMatterController matters() {
      return new WorkflowMatterController(mock(WorkflowMatterQueries.class));
    }

    @Bean
    WorkflowOverviewController overview() {
      return new WorkflowOverviewController(mock(WorkflowOverviewQueries.class));
    }
  }
}
