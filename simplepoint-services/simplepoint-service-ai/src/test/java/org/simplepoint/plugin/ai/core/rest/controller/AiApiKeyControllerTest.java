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
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.service.AiApiKeyService;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiApiKeyControllerTest {

  private AiApiKeyService service;

  private AiApiKeyController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiApiKeyService.class);
    controller = new AiApiKeyController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void rotateSuccessKeepsIssuedKeyResponseContract() throws Exception {
    AiApiKey key = new AiApiKey();
    key.setId("key-1");
    key.setIssuedKey("sp_live_once");
    when(service.rotate("key-1")).thenReturn(key);

    mockMvc.perform(post("/workbench/api-keys/key-1/rotate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("key-1"))
        .andExpect(jsonPath("$.issuedKey").value("sp_live_once"));
  }

  @Test
  void validationFailureReturnsStableJsonWithoutPrivateProse()
      throws Exception {
    String privateProse = "key policy and tenant internals";
    when(service.create(any(AiApiKey.class)))
        .thenThrow(new IllegalArgumentException(privateProse));

    mockMvc.perform(post("/workbench/api-keys")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_API_KEY_REQUEST_INVALID.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void rotationStateFailureReturnsStableJsonWithoutPrivateProse()
      throws Exception {
    String privateProse = "secret hash implementation details";
    when(service.rotate("key-1"))
        .thenThrow(new IllegalStateException(privateProse));

    mockMvc.perform(post("/workbench/api-keys/key-1/rotate"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_API_KEY_OPERATION_UNAVAILABLE.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void unexpectedDeleteFailureReturnsStableServerError() throws Exception {
    String privateProse = "database topology and statement details";
    doThrow(new RuntimeException(privateProse))
        .when(service).removeByIds(any());

    mockMvc.perform(delete("/workbench/api-keys").param("ids", "key-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_API_KEY_OPERATION_FAILED.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void accessDeniedRemainsAccessDenied() {
    AiApiKey key = new AiApiKey();
    when(service.modifyById(key))
        .thenThrow(new AccessDeniedException("denied"));

    assertThrows(AccessDeniedException.class, () -> controller.modify(key));
    verify(service).modifyById(key);
  }
}
