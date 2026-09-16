package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftView;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionDesignerView;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillDraftService;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiSkillVersionDesignerControllerTest {

  private static final String BASE =
      "/workbench/skills/skill-a/versions/version-a/designer";

  private AiSkillDraftService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiSkillDraftService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiSkillVersionDesignerController(service)
    ).build();
  }

  @Test
  void exposesCompatibilityAndBindsCopyRevision() throws Exception {
    when(service.viewVersion("skill-a", "version-a")).thenReturn(
        new SkillVersionDesignerView(
            "skill-a",
            "version-a",
            "1.0.0",
            SkillVersionStatus.PUBLISHED,
            false,
            "future apiVersion",
            null,
            Map.of("apiVersion", "simplepoint.io/v2")
        )
    );
    when(service.copyVersion(
        org.mockito.ArgumentMatchers.eq("skill-a"),
        org.mockito.ArgumentMatchers.eq("version-a"),
        any(SkillDraftRestoreRequest.class)
    )).thenReturn(new SkillDraftView(
        "draft-a",
        "skill-a",
        null,
        null,
        4L,
        null,
        null,
        null,
        null
    ));

    mockMvc.perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.compatible").value(false))
        .andExpect(jsonPath("$.manifest.apiVersion")
            .value("simplepoint.io/v2"));
    mockMvc.perform(post(BASE + "/copy-to-draft")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(4));

    verify(service).copyVersion(
        "skill-a",
        "version-a",
        new SkillDraftRestoreRequest(3L)
    );
  }

  @Test
  void returnsStructuredConflictWhenDraftChanged() throws Exception {
    when(service.copyVersion(
        org.mockito.ArgumentMatchers.eq("skill-a"),
        org.mockito.ArgumentMatchers.eq("version-a"),
        any(SkillDraftRestoreRequest.class)
    )).thenThrow(new SkillDraftConflictException(
        "Skill Draft changed since it was loaded",
        3L,
        4L
    ));

    mockMvc.perform(post(BASE + "/copy-to-draft")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":3}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code")
            .value("SKILL_DRAFT_REVISION_CONFLICT"))
        .andExpect(jsonPath("$.message")
            .value("SKILL_DRAFT_REVISION_CONFLICT"))
        .andExpect(jsonPath("$.currentRevision").value(4))
        .andExpect(content().string(not(containsString(
            "Skill Draft changed since it was loaded"
        ))));
  }
}
