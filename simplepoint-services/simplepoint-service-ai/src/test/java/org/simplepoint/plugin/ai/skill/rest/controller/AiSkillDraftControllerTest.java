package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftDebugExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRun;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftSaveRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftView;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBreakpointsRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventFeed;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillDraftService;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiSkillDraftControllerTest {

  private static final String BASE = "/workbench/skills/skill-a/draft";

  private static final String DOCUMENT_JSON = """
      {
        "schemaVersion": "simplepoint.io/designer/v1alpha1",
        "metadata": {"name": "draft-test", "version": "0.1.0"},
        "inputSchema": {"type": "object"},
        "outputSchema": {"type": "object"},
        "tools": [],
        "nodes": [
          {
            "id": "__input",
            "type": "INPUT",
            "order": 0,
            "configuration": {},
            "position": {"x": 0, "y": 0}
          },
          {
            "id": "__output",
            "type": "OUTPUT",
            "order": 1,
            "configuration": {},
            "position": {"x": 200, "y": 0}
          }
        ],
        "ports": [
          {
            "nodeId": "__input",
            "id": "out",
            "direction": "OUTPUT",
            "kind": "CONTROL",
            "required": true,
            "multiple": false
          },
          {
            "nodeId": "__output",
            "id": "in",
            "direction": "INPUT",
            "kind": "CONTROL",
            "required": true,
            "multiple": false
          }
        ],
        "edges": [
          {
            "id": "edge-0",
            "sourceNodeId": "__input",
            "sourceHandle": "out",
            "targetNodeId": "__output",
            "targetHandle": "in"
          }
        ],
        "tests": [],
        "viewport": {"x": 0, "y": 0, "zoom": 1}
      }
      """;

  private AiSkillDraftService service;

  private AiSkillExecutionService executionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiSkillDraftService.class);
    executionService = mock(AiSkillExecutionService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiSkillDraftController(service, executionService)
    ).setCustomArgumentResolvers(
        new PageableHandlerMethodArgumentResolver()
    ).build();
  }

  @Test
  void bindsSaveRevisionAndCompileRoutes() throws Exception {
    when(service.save(any(), any(SkillDraftSaveRequest.class)))
        .thenAnswer(invocation -> {
          SkillDraftSaveRequest request = invocation.getArgument(1);
          return new SkillDraftView(
              "draft-a",
              "skill-a",
              null,
              null,
              1L,
              request.document(),
              new SkillDesignerCompilationResult(
                  true,
                  null,
                  "hash",
                  List.of()
              ),
              null,
              null
          );
        });
    when(service.findRevisions(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(
            List.of(),
            PageRequest.of(0, 20),
            0
        ));
    when(service.compile("skill-a")).thenReturn(
        new SkillDesignerCompilationResult(true, null, "hash", List.of())
    );

    mockMvc.perform(put(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":0,\"document\":"
                + DOCUMENT_JSON + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
    mockMvc.perform(get(BASE + "/revisions"))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/validate"))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/compile"))
        .andExpect(status().isOk());
    mockMvc.perform(delete(BASE).param("expectedRevision", "1"))
        .andExpect(status().isOk());

    ArgumentCaptor<SkillDraftSaveRequest> request =
        ArgumentCaptor.forClass(SkillDraftSaveRequest.class);
    verify(service).save(org.mockito.ArgumentMatchers.eq("skill-a"),
        request.capture());
    assertThat(request.getValue().document().ports()).hasSize(2);
    assertThat(request.getValue().document().tests()).isEmpty();
    verify(service).remove("skill-a", 1L);
  }

  @Test
  void bindsExactDraftRevisionLiveDebugRoute() throws Exception {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    when(executionService.startDraftDebug(
        any(),
        any(SkillDraftDebugExecutionStartRequest.class)
    )).thenReturn(execution);

    mockMvc.perform(post(BASE + "/debug-executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "revision": 4,
                  "mode": "LIVE",
                  "idempotencyKey": "debug-request-a",
                  "input": {"message": "hello"}
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("execution-a"));

    ArgumentCaptor<SkillDraftDebugExecutionStartRequest> request =
        ArgumentCaptor.forClass(SkillDraftDebugExecutionStartRequest.class);
    verify(executionService).startDraftDebug(
        org.mockito.ArgumentMatchers.eq("skill-a"),
        request.capture()
    );
    assertThat(request.getValue().revision()).isEqualTo(4L);
    assertThat(request.getValue().mode()).isEqualTo(SkillDebugMode.LIVE);
    assertThat(request.getValue().input())
        .containsEntry("message", "hello");
  }

  @Test
  void bindsMockTestRunStartAndDetailRoutes() throws Exception {
    SkillDraftMockTestRun run = new SkillDraftMockTestRun(
        "run-a",
        "skill-a",
        "draft-a",
        5L,
        "a".repeat(64),
        SkillDraftMockTestRunStatus.RUNNING,
        2,
        0,
        0,
        0,
        null,
        null,
        List.of()
    );
    when(executionService.startDraftMockTestRun(
        any(),
        any(SkillDraftMockTestRunStartRequest.class)
    )).thenReturn(run);
    when(executionService.findDraftMockTestRun("skill-a", "run-a"))
        .thenReturn(Optional.of(run));

    mockMvc.perform(post(BASE + "/mock-test-runs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"revision":5,"idempotencyKey":"batch-request-a"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("run-a"))
        .andExpect(jsonPath("$.totalCount").value(2));
    mockMvc.perform(get(BASE + "/mock-test-runs/run-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftRevision").value(5));

    ArgumentCaptor<SkillDraftMockTestRunStartRequest> request =
        ArgumentCaptor.forClass(SkillDraftMockTestRunStartRequest.class);
    verify(executionService).startDraftMockTestRun(
        org.mockito.ArgumentMatchers.eq("skill-a"),
        request.capture()
    );
    assertThat(request.getValue().revision()).isEqualTo(5L);
    assertThat(request.getValue().idempotencyKey())
        .isEqualTo("batch-request-a");
  }

  @Test
  void bindsDraftDebugSafeControlRoutes() throws Exception {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setStatus(SkillExecutionStatus.PAUSED);
    when(executionService.findDraftDebug("skill-a", "execution-a"))
        .thenReturn(Optional.of(execution));
    when(executionService.pause(any(), any(), any())).thenReturn(execution);
    when(executionService.resume(any(), any())).thenReturn(execution);
    when(executionService.cancel(any(), any(), any())).thenReturn(execution);
    when(executionService.setDraftDebugBreakpoints(
        any(), any(), any(SkillExecutionBreakpointsRequest.class)
    )).thenReturn(execution);

    mockMvc.perform(post(BASE + "/debug-executions/execution-a/pause")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Inspect state\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/debug-executions/execution-a/continue"))
        .andExpect(status().isOk());
    mockMvc.perform(post(BASE + "/debug-executions/execution-a/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Done\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(put(BASE + "/debug-executions/execution-a/breakpoints")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"stepIds\":[\"step-a\"]}"))
        .andExpect(status().isOk());

    ArgumentCaptor<SkillExecutionBreakpointsRequest> breakpoints =
        ArgumentCaptor.forClass(SkillExecutionBreakpointsRequest.class);
    verify(executionService).setDraftDebugBreakpoints(
        org.mockito.ArgumentMatchers.eq("skill-a"),
        org.mockito.ArgumentMatchers.eq("execution-a"),
        breakpoints.capture()
    );
    assertThat(breakpoints.getValue().stepIds()).containsExactly("step-a");
  }

  @Test
  void bindsExclusiveDebugEventCursorAndLimit() throws Exception {
    AiSkillExecutionEvent event = new AiSkillExecutionEvent();
    event.setId("event-a");
    event.setSequence(4L);
    event.setType(SkillExecutionEventType.STEP_STARTED);
    event.setExecutionStatus(SkillExecutionStatus.RUNNING);
    when(executionService.findDraftDebugEvents(
        "skill-a",
        "execution-a",
        3L,
        25
    )).thenReturn(new SkillExecutionEventFeed(
        3L,
        4L,
        false,
        SkillExecutionStatus.RUNNING,
        List.of(event)
    ));

    mockMvc.perform(get(BASE + "/debug-executions/execution-a/events")
            .param("afterSequence", "3")
            .param("limit", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.afterSequence").value(3))
        .andExpect(jsonPath("$.nextSequence").value(4))
        .andExpect(jsonPath("$.events[0].type").value("STEP_STARTED"));

    verify(executionService).findDraftDebugEvents(
        "skill-a",
        "execution-a",
        3L,
        25
    );
  }

  @Test
  void returnsNotFoundWhenDraftDoesNotExist() throws Exception {
    when(service.find("skill-a")).thenReturn(Optional.empty());

    mockMvc.perform(get(BASE)).andExpect(status().isNotFound());
  }

  @Test
  void returnsStructuredConflictForStaleRevision() throws Exception {
    when(service.save(any(), any(SkillDraftSaveRequest.class)))
        .thenThrow(new SkillDraftConflictException(
            "Skill Draft changed since it was loaded",
            2L,
            3L
        ));

    mockMvc.perform(put(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":2,\"document\":"
                + DOCUMENT_JSON + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code")
            .value("SKILL_DRAFT_REVISION_CONFLICT"))
        .andExpect(jsonPath("$.message")
            .value("SKILL_DRAFT_REVISION_CONFLICT"))
        .andExpect(jsonPath("$.expectedRevision").value(2))
        .andExpect(jsonPath("$.currentRevision").value(3))
        .andExpect(content().string(not(containsString(
            "Skill Draft changed since it was loaded"
        ))));
  }
}
