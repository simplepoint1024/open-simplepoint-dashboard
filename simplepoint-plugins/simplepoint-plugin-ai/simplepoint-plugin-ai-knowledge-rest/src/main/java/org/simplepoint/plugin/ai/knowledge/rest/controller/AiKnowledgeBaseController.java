package org.simplepoint.plugin.ai.knowledge.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.simplepoint.plugin.ai.knowledge.api.constants.AiKnowledgePaths;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeBaseService;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeDocumentService;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeRetrievalRequest;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeTextDocumentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge base configuration, ingestion and retrieval endpoints.
 */
@RestController
@RequestMapping({
    AiKnowledgePaths.PLATFORM_KNOWLEDGE_BASES,
    AiKnowledgePaths.TENANT_KNOWLEDGE_BASES
})
@Tag(name = "AI知识库", description = "管理知识库、文档索引和混合检索")
public class AiKnowledgeBaseController
    extends BaseController<AiKnowledgeBaseService, AiKnowledgeBase, String> {

  private static final MediaType UTF8_TEXT_PLAIN = new MediaType(
      "text", "plain", StandardCharsets.UTF_8
  );

  private final AiKnowledgeDocumentService documentService;

  private final AiModelDefinitionService modelService;

  /**
   * Creates the controller.
   */
  public AiKnowledgeBaseController(
      final AiKnowledgeBaseService service,
      final AiKnowledgeDocumentService documentService,
      final AiModelDefinitionService modelService
  ) {
    super(service);
    this.documentService = documentService;
    this.modelService = modelService;
  }

  /**
   * Lists embedding models visible to the current knowledge-base scope.
   */
  @GetMapping("/embedding-models")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.view', 'ai.knowledge-bases.view')")
  @Operation(summary = "查询知识库可用的 Embedding 模型")
  public Response<?> embeddingModels() {
    return ok(modelService.listAvailableModels().stream()
        .filter(model -> model.getModelType() == AiModelType.EMBEDDING)
        .toList());
  }

  /**
   * Pages knowledge bases in the current ownership scope.
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.view', 'ai.knowledge-bases.view')")
  @Operation(summary = "分页查询知识库")
  public Response<Page<AiKnowledgeBase>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiKnowledgeBase.class);
  }

  /**
   * Creates a knowledge base.
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.create', 'ai.knowledge-bases.create')")
  @Operation(summary = "新增知识库")
  public Response<?> add(@RequestBody final AiKnowledgeBase data) {
    return invoke(() -> service.create(data));
  }

  /**
   * Updates a knowledge base.
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.edit', 'ai.knowledge-bases.edit')")
  @Operation(summary = "修改知识库")
  public Response<?> modify(@RequestBody final AiKnowledgeBase data) {
    return invoke(() -> service.modifyById(data));
  }

  /**
   * Deletes knowledge bases and their document chunks.
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.delete', 'ai.knowledge-bases.delete')")
  @Operation(summary = "删除知识库")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
  }

  /**
   * Pages source documents.
   */
  @GetMapping("/{knowledgeBaseId}/documents")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.documents', 'ai.knowledge-bases.documents')")
  @Operation(summary = "分页查询知识库文档")
  public Response<Page<AiKnowledgeDocument>> documents(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(
        documentService.limit(knowledgeBaseId, attributes, pageable),
        AiKnowledgeDocument.class
    );
  }

  /**
   * Uploads a common document format and enqueues indexing.
   */
  @PostMapping(
      value = "/{knowledgeBaseId}/documents/upload",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.documents', 'ai.knowledge-bases.documents')")
  @Operation(summary = "上传知识库文档并提交索引任务")
  public Response<?> upload(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @RequestParam("file") final MultipartFile file,
      @RequestParam(value = "metadataJson", required = false) final String metadataJson
  ) {
    return accepted(() -> documentService.upload(knowledgeBaseId, file, metadataJson));
  }

  /**
   * Adds plain text and enqueues indexing.
   */
  @PostMapping("/{knowledgeBaseId}/documents/text")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.documents', 'ai.knowledge-bases.documents')")
  @Operation(summary = "新增文本文档并提交索引任务")
  public Response<?> addText(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @RequestBody final AiKnowledgeTextDocumentRequest request
  ) {
    return accepted(() -> documentService.addText(knowledgeBaseId, request));
  }

  /**
   * Enqueues a new index generation for one document.
   */
  @PostMapping("/{knowledgeBaseId}/documents/{documentId}/reindex")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.documents', 'ai.knowledge-bases.documents')")
  @Operation(summary = "提交知识库文档重新索引任务")
  public Response<?> reindex(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @PathVariable("documentId") final String documentId
  ) {
    return accepted(() -> documentService.reindex(knowledgeBaseId, documentId));
  }

  /**
   * Deletes source documents and their chunks.
   */
  @DeleteMapping("/{knowledgeBaseId}/documents")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.documents', 'ai.knowledge-bases.documents')")
  @Operation(summary = "删除知识库文档")
  public Response<?> removeDocuments(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @RequestParam("ids") final String ids
  ) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      documentService.remove(knowledgeBaseId, idSet);
      return idSet;
    });
  }

  /**
   * Retrieves ranked chunks with vector, keyword or hybrid search.
   */
  @PostMapping("/{knowledgeBaseId}/retrieve")
  @PreAuthorize("hasRole('Administrator') or hasAnyAuthority('ai.system.knowledge-bases.retrieve', 'ai.knowledge-bases.retrieve')")
  @Operation(summary = "检索知识库")
  public Response<?> retrieve(
      @PathVariable("knowledgeBaseId") final String knowledgeBaseId,
      @RequestBody final AiKnowledgeRetrievalRequest request
  ) {
    return invoke(() -> service.retrieve(knowledgeBaseId, request));
  }

  private Response<?> invoke(final java.util.function.Supplier<?> operation) {
    try {
      return ok(operation.get());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(UTF8_TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }

  private Response<?> accepted(final java.util.function.Supplier<?> operation) {
    try {
      return Response.of(ResponseEntity.accepted().body(operation.get()));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(UTF8_TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }
}
