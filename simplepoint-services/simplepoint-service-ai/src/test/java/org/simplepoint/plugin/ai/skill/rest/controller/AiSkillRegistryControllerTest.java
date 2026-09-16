package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillManagedRegistryStatus;
import org.simplepoint.plugin.ai.skill.api.service.SkillManagedRegistryService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiSkillRegistryControllerTest {

  private SkillManagedRegistryService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = org.mockito.Mockito.mock(SkillManagedRegistryService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiSkillRegistryController(service)
    ).build();
  }

  @Test
  void exposesCredentialFreeConfigurationAndConnectivityRoutes()
      throws Exception {
    when(service.describe()).thenReturn(registryStatus(null));
    when(service.checkConnectivity()).thenReturn(registryStatus(true));

    mockMvc.perform(get("/workbench/skills/managed-registry"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registry").value("registry.example.com"))
        .andExpect(jsonPath("$.authenticationConfigured").value(true));
    mockMvc.perform(post(
            "/workbench/skills/managed-registry/connectivity-check"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connected").value(true));

    verify(service).describe();
    verify(service).checkConnectivity();
  }

  @Test
  void returnsStructuredConnectivityFailure() throws Exception {
    when(service.checkConnectivity()).thenThrow(
        new IllegalStateException("Registry is unreachable")
    );

    mockMvc.perform(post(
            "/workbench/skills/managed-registry/connectivity-check"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code")
            .value("SKILL_MANAGED_REGISTRY_UNAVAILABLE"))
        .andExpect(jsonPath("$.message")
            .value("SKILL_MANAGED_REGISTRY_UNAVAILABLE"))
        .andExpect(content().string(not(containsString(
            "Registry is unreachable"
        ))));
  }

  private SkillManagedRegistryStatus registryStatus(final Boolean connected) {
    return new SkillManagedRegistryStatus(
        true,
        "registry.example.com",
        "simplepoint/skills",
        true,
        true,
        true,
        connected,
        connected == null ? null : Instant.parse("2026-08-08T00:00:00Z"),
        connected == null
            ? "SKILL_REGISTRY_CHECK_REQUIRED"
            : "SKILL_REGISTRY_CONNECTED"
    );
  }
}
