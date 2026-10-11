package org.simplepoint.plugin.ai.knowledge.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.core.api.model.AiEmbeddingModelOption;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeBaseService;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeDocumentService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiKnowledgeBaseControllerTest {

  @Test
  void documentSchemaAndPagingTransportUseTheSameOwnedKnowledgeBase() throws Exception {
    var documents = mock(AiKnowledgeDocumentService.class);
    when(documents.querySchema("base-1"))
        .thenReturn(Map.of("schema", Map.of("type", "object"), "buttons", List.of()));
    when(documents.limit(org.mockito.ArgumentMatchers.eq("base-1"), any(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new AiKnowledgeBaseController(
                    mock(AiKnowledgeBaseService.class),
                    documents,
                    mock(AiModelDefinitionService.class)))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    mvc.perform(get("/workbench/knowledge-bases/base-1/documents/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.type").value("object"));
    mvc.perform(
            get("/workbench/knowledge-bases/base-1/documents")
                .param("status", "equals:READY")
                .param("page", "1")
                .param("size", "5")
                .param("sort", "chunkCount,desc"))
        .andExpect(status().isOk());
    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(documents)
        .limit(
            org.mockito.ArgumentMatchers.eq("base-1"),
            org.mockito.ArgumentMatchers.argThat(
                filters -> "equals:READY".equals(filters.get("status"))),
            pageable.capture());
    org.assertj.core.api.Assertions.assertThat(pageable.getValue())
        .isEqualTo(PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "chunkCount")));
    org.assertj.core.api.Assertions.assertThat(
            AiKnowledgeBaseController.class
                .getMethod("documentSchema", String.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)
                .value())
        .isEqualTo(
            "hasRole('Administrator') or hasAuthority('ai.workbench.knowledge-bases.documents')");
  }

  @Test
  void documentLimitsRouteReturnsConfiguredLimitAndRequiresDocumentAuthority() throws Exception {
    var documents = mock(AiKnowledgeDocumentService.class);
    when(documents.ingestionLimits()).thenReturn(Map.of("maxUploadBytes", 65536L));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new AiKnowledgeBaseController(
                    mock(AiKnowledgeBaseService.class),
                    documents,
                    mock(AiModelDefinitionService.class)))
            .build();
    mvc.perform(get("/workbench/knowledge-bases/document-limits"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxUploadBytes").value(65536));
    org.assertj.core.api.Assertions.assertThat(
            AiKnowledgeBaseController.class
                .getMethod("documentLimits")
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)
                .value())
        .isEqualTo(
            "hasRole('Administrator') or hasAuthority('ai.workbench.knowledge-bases.documents')");
  }

  @Test
  void embeddingSelectorUsesPagedProjectionContractRatherThanLegacyFullCatalog() throws Exception {
    var models = mock(AiModelDefinitionService.class);
    when(models.embeddingModelOptions(any(), any()))
        .thenReturn(
            new PageImpl<>(
                List.of(new AiEmbeddingModelOption("actual-selected", "Actual Embedding")),
                PageRequest.of(26, 20, Sort.by("name")),
                521));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new AiKnowledgeBaseController(
                    mock(AiKnowledgeBaseService.class),
                    mock(AiKnowledgeDocumentService.class),
                    models))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    mvc.perform(
            get("/workbench/knowledge-bases/embedding-model-options")
                .param("page", "26")
                .param("size", "20")
                .param("sort", "name,asc")
                .param("name", "like:Actual"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("actual-selected"))
        .andExpect(jsonPath("$.content[0].name").value("Actual Embedding"))
        .andExpect(jsonPath("$.totalElements").value(521));
    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(models)
        .embeddingModelOptions(
            org.mockito.ArgumentMatchers.<Map<String, String>>any(), pageable.capture());
    org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(26);
    org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    verify(models, never()).listAvailableModels();
  }

  @Test
  void invalidRequestDoesNotExposeServiceExceptionMessage() throws Exception {
    AiKnowledgeBaseService service = mock(AiKnowledgeBaseService.class);
    doThrow(new IllegalArgumentException("private-vector-store-detail"))
        .when(service)
        .create(any(AiKnowledgeBase.class));
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AiKnowledgeBaseController(
                    service,
                    mock(AiKnowledgeDocumentService.class),
                    mock(AiModelDefinitionService.class)))
            .build();

    mockMvc
        .perform(
            post("/workbench/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("AI_KNOWLEDGE_REQUEST_INVALID"))
        .andExpect(content().string(not(containsString("private-vector-store-detail"))));
  }
}
