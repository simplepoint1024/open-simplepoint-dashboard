package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiSkillControllerTest {

  private AiSkillExecutionService executionService;

  private AiSkillService skillService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    executionService = mock(AiSkillExecutionService.class);
    skillService = mock(AiSkillService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiSkillController(skillService, executionService)
    ).setCustomArgumentResolvers(
        new PageableHandlerMethodArgumentResolver()
    ).build();
  }

  @Test
  void deleteReturnsAnEmptySuccessfulBody() throws Exception {
    mockMvc.perform(delete("/workbench/skills/skill-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    verify(skillService).remove("skill-a");
  }

  @Test
  void invalidRequestDoesNotExposeServiceExceptionMessage() throws Exception {
    when(skillService.findAll(any(Pageable.class))).thenThrow(
        new IllegalArgumentException("private-skill-registry-detail")
    );

    mockMvc.perform(get("/workbench/skills"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_SKILL_REQUEST_INVALID"
        ))
        .andExpect(content().string(not(containsString(
            "private-skill-registry-detail"
        ))));
  }

  @Test
  void startsExecutionWithIdempotencyKeyAndJsonInput() throws Exception {
    AiSkillExecution execution = execution();
    when(executionService.start(
        eq("skill-a"),
        any(SkillExecutionStartRequest.class)
    )).thenReturn(execution);

    mockMvc.perform(post("/workbench/skills/skill-a/executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "idempotencyKey": "request-a",
                  "input": {
                    "message": "hello"
                  }
                }
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<SkillExecutionStartRequest> request =
        ArgumentCaptor.forClass(SkillExecutionStartRequest.class);
    verify(executionService).start(eq("skill-a"), request.capture());
    assertThat(request.getValue().idempotencyKey()).isEqualTo("request-a");
    assertThat(request.getValue().input())
        .isEqualTo(Map.of("message", "hello"));
  }

  @Test
  void bindsExecutionHistoryAndDetailRoutes() throws Exception {
    AiSkillExecution execution = execution();
    when(executionService.findAll(eq("skill-a"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(
            List.of(),
            PageRequest.of(0, 20),
            0
        ));
    when(executionService.find("skill-a", "execution-a"))
        .thenReturn(Optional.of(execution));

    mockMvc.perform(get("/workbench/skills/skill-a/executions")
            .param("page", "0")
            .param("size", "20"))
        .andExpect(status().isOk());
    mockMvc.perform(get(
            "/workbench/skills/skill-a/executions/execution-a"))
        .andExpect(status().isOk());

    verify(executionService).findAll(eq("skill-a"), any(Pageable.class));
    verify(executionService).find("skill-a", "execution-a");
  }

  @Test
  void bindsApprovalPauseAndResumeRoutes() throws Exception {
    AiSkillExecution execution = execution();
    when(executionService.approve(
        eq("skill-a"),
        eq("execution-a"),
        any(SkillExecutionDecisionRequest.class)
    )).thenReturn(execution);
    when(executionService.reject(
        eq("skill-a"),
        eq("execution-a"),
        any(SkillExecutionDecisionRequest.class)
    )).thenReturn(execution);
    when(executionService.pause(
        eq("skill-a"),
        eq("execution-a"),
        any(SkillExecutionPauseRequest.class)
    )).thenReturn(execution);
    when(executionService.resume("skill-a", "execution-a"))
        .thenReturn(execution);

    String base = "/workbench/skills/skill-a/executions/execution-a";
    mockMvc.perform(post(base + "/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"approved\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post(base + "/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"rejected\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post(base + "/pause")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"maintenance\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post(base + "/resume"))
        .andExpect(status().isOk());

    verify(executionService).resume("skill-a", "execution-a");
  }

  private static AiSkillExecution execution() {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setSkillVersionId("version-a");
    execution.setStatus(SkillExecutionStatus.PENDING);
    execution.setAttemptCount(0);
    execution.setInput(Map.of("message", "hello"));
    return execution;
  }
}
