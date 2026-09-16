package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftPublishRequest;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillPublishService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiSkillPublishControllerTest {

  private AiSkillPublishService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiSkillPublishService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiSkillPublishController(service)
    ).setCustomArgumentResolvers(
        new PageableHandlerMethodArgumentResolver()
    ).build();
  }

  @Test
  void bindsStartHistoryDetailAndRetryRoutes() throws Exception {
    AiSkillPublishTask task = new AiSkillPublishTask();
    task.setId("task-a");
    when(service.start(eq("skill-a"), any())).thenReturn(task);
    when(service.findAll(eq("skill-a"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(
            new java.util.ArrayList<>(),
            PageRequest.of(0, 20),
            0
        ));
    when(service.find("skill-a", "task-a")).thenReturn(Optional.of(task));
    when(service.retry("skill-a", "task-a")).thenReturn(task);

    mockMvc.perform(post("/workbench/skills/skill-a/draft/publish-tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"draftRevision":4,"version":"2.0.0",
                 "activate":true,"idempotencyKey":"request-a"}
                """))
        .andExpect(status().isOk());
    mockMvc.perform(get("/workbench/skills/skill-a/draft/publish-tasks"))
        .andExpect(status().isOk());
    mockMvc.perform(get(
            "/workbench/skills/skill-a/draft/publish-tasks/task-a"))
        .andExpect(status().isOk());
    mockMvc.perform(post(
            "/workbench/skills/skill-a/draft/publish-tasks/task-a/retry"))
        .andExpect(status().isOk());

    verify(service).start(
        eq("skill-a"),
        any(SkillDraftPublishRequest.class)
    );
    verify(service).retry("skill-a", "task-a");
  }

  @Test
  void publishFailureDoesNotExposeRegistryExceptionMessage() throws Exception {
    when(service.start(eq("skill-a"), any()))
        .thenThrow(new IllegalStateException("private-registry-response"));

    mockMvc.perform(post("/workbench/skills/skill-a/draft/publish-tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"draftRevision":4,"version":"2.0.0",
                 "activate":true,"idempotencyKey":"request-a"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code")
            .value("SKILL_PUBLISH_REQUEST_INVALID"))
        .andExpect(jsonPath("$.message")
            .value("SKILL_PUBLISH_REQUEST_INVALID"))
        .andExpect(content().string(not(containsString(
            "private-registry-response"
        ))));
  }
}
