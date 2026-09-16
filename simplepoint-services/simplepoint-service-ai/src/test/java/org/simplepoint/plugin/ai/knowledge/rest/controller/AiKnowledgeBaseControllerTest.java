package org.simplepoint.plugin.ai.knowledge.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeBaseService;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeDocumentService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiKnowledgeBaseControllerTest {

  @Test
  void invalidRequestDoesNotExposeServiceExceptionMessage() throws Exception {
    AiKnowledgeBaseService service = mock(AiKnowledgeBaseService.class);
    doThrow(new IllegalArgumentException("private-vector-store-detail"))
        .when(service).create(any(AiKnowledgeBase.class));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
        new AiKnowledgeBaseController(
            service,
            mock(AiKnowledgeDocumentService.class),
            mock(AiModelDefinitionService.class)
        )
    ).build();

    mockMvc.perform(post("/workbench/knowledge-bases")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_KNOWLEDGE_REQUEST_INVALID"
        ))
        .andExpect(content().string(not(containsString(
            "private-vector-store-detail"
        ))));
  }
}
