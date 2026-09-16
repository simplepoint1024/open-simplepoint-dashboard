package org.simplepoint.plugin.ai.core.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiModelDefinitionControllerTest {

  private AiModelDefinitionService service;

  private AiModelDefinitionController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiModelDefinitionService.class);
    controller = new AiModelDefinitionController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void createSuccessKeepsExistingResponseContract() throws Exception {
    AiModelDefinition model = new AiModelDefinition();
    model.setId("model-1");
    when(service.create(any(AiModelDefinition.class))).thenReturn(model);

    mockMvc.perform(post("/workbench/models")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("model-1"));
  }

  @Test
  void validationFailureReturnsStableJsonWithoutPrivateProse()
      throws Exception {
    String privateProse = "model, provider, and tenant internals";
    when(service.create(any(AiModelDefinition.class)))
        .thenThrow(new IllegalArgumentException(privateProse));

    mockMvc.perform(post("/workbench/models")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_MODEL_REQUEST_INVALID.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void deleteStateFailureReturnsStableJsonWithoutPrivateProse()
      throws Exception {
    String privateProse = "dependent invocation identifiers";
    doThrow(new IllegalStateException(privateProse))
        .when(service).removeByIds(any());

    mockMvc.perform(delete("/workbench/models").param("ids", "model-1"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_MODEL_OPERATION_UNAVAILABLE.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void unexpectedUpdateFailureReturnsStableServerError() throws Exception {
    String privateProse = "database topology and statement details";
    when(service.modifyById(any(AiModelDefinition.class)))
        .thenThrow(new RuntimeException(privateProse));

    mockMvc.perform(put("/workbench/models")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_MODEL_OPERATION_FAILED.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void accessDeniedRemainsAccessDenied() {
    AiModelDefinition model = new AiModelDefinition();
    when(service.create(model))
        .thenThrow(new AccessDeniedException("denied"));

    assertThrows(AccessDeniedException.class, () -> controller.add(model));
    verify(service).create(model);
  }
}
