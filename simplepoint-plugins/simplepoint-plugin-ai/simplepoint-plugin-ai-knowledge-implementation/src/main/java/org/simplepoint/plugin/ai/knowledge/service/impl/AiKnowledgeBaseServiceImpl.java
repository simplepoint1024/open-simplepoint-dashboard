package org.simplepoint.plugin.ai.knowledge.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiEmbeddingService;
import org.simplepoint.plugin.ai.core.api.vo.AiEmbeddingResult;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeBaseService;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeRetrievalRequest;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeRetrievalResult;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchSpec;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge base management and retrieval implementation.
 */
@Service
public class AiKnowledgeBaseServiceImpl
    extends BaseServiceImpl<AiKnowledgeBaseRepository, AiKnowledgeBase, String>
    implements AiKnowledgeBaseService {

  private final AiKnowledgeBaseRepository repository;

  private final AiKnowledgeDocumentRepository documentRepository;

  private final AiKnowledgeChunkRepository chunkRepository;

  private final AiKnowledgeIndexJobRepository indexJobRepository;

  private final AiModelDefinitionRepository modelRepository;

  private final AiEmbeddingService embeddingService;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiKnowledgeProperties properties;

  private final ObjectStorageRemoteService objectStorageRemoteService;

  /**
   * Creates the knowledge base service.
   */
  public AiKnowledgeBaseServiceImpl(
      final AiKnowledgeBaseRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiKnowledgeDocumentRepository documentRepository,
      final AiKnowledgeChunkRepository chunkRepository,
      final AiKnowledgeIndexJobRepository indexJobRepository,
      final AiModelDefinitionRepository modelRepository,
      final AiEmbeddingService embeddingService,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiKnowledgeProperties properties,
      final ObjectStorageRemoteService objectStorageRemoteService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.indexJobRepository = indexJobRepository;
    this.modelRepository = modelRepository;
    this.embeddingService = embeddingService;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.properties = properties;
    this.objectStorageRemoteService = objectStorageRemoteService;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends AiKnowledgeBase> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    normalized.put("tenantId", scope.tenantId() == null ? "is:null" : scope.tenantId());
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    Page<S> page = super.limit(normalized, pageable);
    page.getContent().forEach(this::decorate);
    return page;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiKnowledgeBase> S create(final S entity) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    entity.setScopeType(scope.scopeType());
    entity.setTenantId(scope.tenantId());
    normalizeAndValidate(entity, null);
    S saved = super.create(entity);
    if (saved.getScopeType() == null) {
      saved.setScopeType(scope.scopeType());
      saved.setTenantId(scope.tenantId());
      repository.save(saved);
    }
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiKnowledgeBase> AiKnowledgeBase modifyById(final S entity) {
    AiKnowledgeBase current = findOwned(requireEntityId(entity));
    entity.setScopeType(current.getScopeType());
    entity.setTenantId(current.getTenantId());
    normalizeAndValidate(entity, current.getId());
    assertEmbeddingConfigurationCanChange(current, entity);
    AiKnowledgeBase updated = (AiKnowledgeBase) super.modifyById(entity);
    decorate(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<AiKnowledgeBase> findById(final String id) {
    Optional<AiKnowledgeBase> result = repository.findActiveById(requireValue(id, "知识库 ID 不能为空"));
    result.ifPresent(item -> scopeAccessPolicy.assertCanReadManagedResource(
        item.getScopeType(), item.getTenantId()
    ));
    result.ifPresent(this::decorate);
    return result;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<AiKnowledgeBase> knowledgeBases = ids.stream().map(this::findOwned).toList();
    for (AiKnowledgeBase knowledgeBase : knowledgeBases) {
      indexJobRepository.deleteByKnowledgeBaseId(knowledgeBase.getId());
      chunkRepository.deleteByKnowledgeBaseId(knowledgeBase.getId());
      List<AiKnowledgeDocument> documents = documentRepository
          .findAllActiveByKnowledgeBaseId(knowledgeBase.getId());
      List<String> documentIds = documents.stream()
          .map(AiKnowledgeDocument::getId)
          .toList();
      if (!documentIds.isEmpty()) {
        documentRepository.deleteByIds(documentIds);
      }
      documents.stream()
          .map(AiKnowledgeDocument::getStorageObjectId)
          .map(AiKnowledgeBaseServiceImpl::trimToNull)
          .filter(java.util.Objects::nonNull)
          .forEach(objectStorageRemoteService::delete);
    }
    super.removeByIds(ids);
  }

  /** {@inheritDoc} */
  @Override
  public AiKnowledgeRetrievalResult retrieve(
      final String knowledgeBaseId,
      final AiKnowledgeRetrievalRequest request
  ) {
    AiKnowledgeBase knowledgeBase = findOwned(knowledgeBaseId);
    if (!Boolean.TRUE.equals(knowledgeBase.getEnabled())) {
      throw new IllegalStateException("知识库未启用");
    }
    if (request == null) {
      throw new IllegalArgumentException("检索请求不能为空");
    }
    String query = requireValue(request.getQuery(), "检索问题不能为空");
    AiKnowledgeRetrievalMode mode = request.getMode() == null
        ? knowledgeBase.getRetrievalMode() : request.getMode();
    int topK = request.getTopK() == null ? knowledgeBase.getTopK() : request.getTopK();
    double threshold = request.getScoreThreshold() == null
        ? knowledgeBase.getScoreThreshold() : request.getScoreThreshold();
    validateRange(topK, 1, 100, "Top K");
    validateUnitInterval(threshold, "最低相关度");
    List<Double> queryEmbedding = null;
    if (mode != AiKnowledgeRetrievalMode.KEYWORD) {
      validateEmbeddingModel(knowledgeBase);
      AiEmbeddingResult embedding = embeddingService.embed(
          knowledgeBase.getEmbeddingModelId(),
          List.of(query),
          knowledgeBase.getEmbeddingDimensions()
      );
      validateEmbeddingDimensions(knowledgeBase, embedding.dimensions());
      queryEmbedding = embedding.vectors().getFirst();
    }
    AiKnowledgeSearchSpec spec = new AiKnowledgeSearchSpec(
        knowledgeBase.getId(),
        query,
        mode,
        queryEmbedding,
        request.getDocumentIds(),
        topK,
        threshold,
        knowledgeBase.getVectorWeight(),
        knowledgeBase.getKeywordWeight()
    );
    return new AiKnowledgeRetrievalResult(query, mode, chunkRepository.search(spec));
  }

  private void normalizeAndValidate(final AiKnowledgeBase entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("知识库配置不能为空");
    }
    entity.setName(requireValue(entity.getName(), "知识库名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "知识库编码不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    entity.setEmbeddingModelId(trimToNull(entity.getEmbeddingModelId()));
    entity.setChunkSize(entity.getChunkSize() == null ? 1000 : entity.getChunkSize());
    entity.setChunkOverlap(entity.getChunkOverlap() == null ? 150 : entity.getChunkOverlap());
    entity.setRetrievalMode(entity.getRetrievalMode() == null
        ? AiKnowledgeRetrievalMode.HYBRID : entity.getRetrievalMode());
    entity.setTopK(entity.getTopK() == null ? 10 : entity.getTopK());
    entity.setScoreThreshold(entity.getScoreThreshold() == null
        ? 0.0D : entity.getScoreThreshold());
    entity.setVectorWeight(entity.getVectorWeight() == null ? 0.7D : entity.getVectorWeight());
    entity.setKeywordWeight(entity.getKeywordWeight() == null ? 0.3D : entity.getKeywordWeight());
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    validateRange(entity.getChunkSize(), 100, 8000, "分块大小");
    validateRange(entity.getChunkOverlap(), 0, 2000, "分块重叠");
    if (entity.getChunkOverlap() >= entity.getChunkSize()) {
      throw new IllegalArgumentException("分块重叠必须小于分块大小");
    }
    validateRange(entity.getTopK(), 1, 100, "Top K");
    validateUnitInterval(entity.getScoreThreshold(), "最低相关度");
    validateUnitInterval(entity.getVectorWeight(), "向量权重");
    validateUnitInterval(entity.getKeywordWeight(), "关键词权重");
    double totalWeight = entity.getVectorWeight() + entity.getKeywordWeight();
    if (totalWeight <= 0.0D) {
      throw new IllegalArgumentException("向量权重和关键词权重不能同时为 0");
    }
    entity.setVectorWeight(entity.getVectorWeight() / totalWeight);
    entity.setKeywordWeight(entity.getKeywordWeight() / totalWeight);
    if (entity.getEmbeddingDimensions() != null) {
      validateRange(
          entity.getEmbeddingDimensions(),
          1,
          properties.getStoredVectorDimensions(),
          "Embedding 维度"
      );
    }
    if (entity.getRetrievalMode() != AiKnowledgeRetrievalMode.KEYWORD) {
      validateEmbeddingModel(entity);
    }
    repository.findActiveByCodeAndScope(
        entity.getCode(), entity.getScopeType(), entity.getTenantId()
    ).filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("当前作用域已存在知识库编码: " + entity.getCode());
        });
  }

  private void validateEmbeddingModel(final AiKnowledgeBase knowledgeBase) {
    String modelId = requireValue(
        knowledgeBase.getEmbeddingModelId(),
        "向量或混合检索必须配置 Embedding 模型"
    );
    AiModelDefinition model = modelRepository.findActiveById(modelId)
        .orElseThrow(() -> new IllegalArgumentException("Embedding 模型不存在: " + modelId));
    if (model.getModelType() != AiModelType.EMBEDDING
        || !Boolean.TRUE.equals(model.getEnabled())
        || !Boolean.TRUE.equals(model.getAvailable())
        || !scopeAccessPolicy.canUseResource(model.getScopeType(), model.getTenantId())) {
      throw new IllegalArgumentException("所选模型不是当前作用域可用的 Embedding 模型");
    }
  }

  private void validateEmbeddingDimensions(
      final AiKnowledgeBase knowledgeBase,
      final int actualDimensions
  ) {
    if (actualDimensions > properties.getStoredVectorDimensions()) {
      throw new IllegalArgumentException(
          "模型返回 " + actualDimensions + " 维向量，当前 pgvector 索引最多存储 "
              + properties.getStoredVectorDimensions() + " 维；请配置较小的输出维度"
      );
    }
    if (knowledgeBase.getEmbeddingDimensions() != null
        && knowledgeBase.getEmbeddingDimensions() != actualDimensions) {
      throw new IllegalStateException(
          "模型实际返回维度与知识库配置不一致: " + actualDimensions
      );
    }
  }

  private void assertEmbeddingConfigurationCanChange(
      final AiKnowledgeBase current,
      final AiKnowledgeBase requested
  ) {
    long chunks = chunkRepository.countByKnowledgeBaseId(current.getId());
    if (chunks == 0) {
      return;
    }
    if (!java.util.Objects.equals(current.getEmbeddingModelId(), requested.getEmbeddingModelId())
        || !java.util.Objects.equals(
            current.getEmbeddingDimensions(), requested.getEmbeddingDimensions())) {
      throw new IllegalArgumentException("知识库已有文档，修改 Embedding 配置前请先清空文档");
    }
  }

  private AiKnowledgeBase findOwned(final String id) {
    AiKnowledgeBase knowledgeBase = repository.findActiveById(requireValue(id, "知识库 ID 不能为空"))
        .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));
    scopeAccessPolicy.assertCanManageOwnedResource(
        knowledgeBase.getScopeType(), knowledgeBase.getTenantId()
    );
    return knowledgeBase;
  }

  private void decorate(final AiKnowledgeBase knowledgeBase) {
    knowledgeBase.setScopeType(AiScopeAccessPolicy.effectiveScope(knowledgeBase.getScopeType()));
    knowledgeBase.setDocumentCount(
        documentRepository.countActiveByKnowledgeBaseId(knowledgeBase.getId())
    );
    knowledgeBase.setChunkCount(chunkRepository.countByKnowledgeBaseId(knowledgeBase.getId()));
  }

  private static void validateRange(
      final int value,
      final int minimum,
      final int maximum,
      final String field
  ) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          field + "必须在 " + minimum + " 到 " + maximum + " 之间"
      );
    }
  }

  private static void validateUnitInterval(final double value, final String field) {
    if (Double.isNaN(value) || value < 0.0D || value > 1.0D) {
      throw new IllegalArgumentException(field + "必须在 0 到 1 之间");
    }
  }

  private static String requireEntityId(final AiKnowledgeBase entity) {
    if (entity == null) {
      throw new IllegalArgumentException("知识库配置不能为空");
    }
    return requireValue(entity.getId(), "知识库 ID 不能为空");
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }
}
