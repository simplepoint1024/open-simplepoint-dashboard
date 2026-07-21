package org.simplepoint.plugin.ai.knowledge.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeChunkRecord;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchHit;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchSpec;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL full-text, trigram and pgvector chunk repository.
 */
@Repository
public class PgVectorKnowledgeChunkRepository implements AiKnowledgeChunkRepository {

  private final JdbcTemplate jdbcTemplate;

  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  private final int storedVectorDimensions;

  private final int hybridCandidateMultiplier;

  private final int hybridRrfK;

  private final int maxRetrievalCandidates;

  /**
   * Creates the repository.
   */
  public PgVectorKnowledgeChunkRepository(
      final JdbcTemplate jdbcTemplate,
      final AiKnowledgeProperties properties
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    this.storedVectorDimensions = properties.getStoredVectorDimensions();
    this.hybridCandidateMultiplier = positive(properties.getHybridCandidateMultiplier(), 5);
    this.hybridRrfK = positive(properties.getHybridRrfK(), 60);
    this.maxRetrievalCandidates = positive(properties.getMaxRetrievalCandidates(), 1000);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void replaceDocumentChunks(
      final String documentId,
      final List<AiKnowledgeChunkRecord> chunks
  ) {
    deleteByDocumentId(documentId);
    if (chunks == null || chunks.isEmpty()) {
      return;
    }
    final String sql = """
        insert into simpoint_ai_knowledge_chunks (
          id, knowledge_base_id, document_id, scope_type, tenant_id, chunk_index,
          content, metadata_json, character_count, embedding, embedding_dimensions, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?, current_timestamp)
        """;
    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(final PreparedStatement statement, final int index)
          throws SQLException {
        AiKnowledgeChunkRecord chunk = chunks.get(index);
        statement.setString(1, chunk.id());
        statement.setString(2, chunk.knowledgeBaseId());
        statement.setString(3, chunk.documentId());
        statement.setString(4, chunk.scopeType().name());
        statement.setString(5, chunk.tenantId());
        statement.setInt(6, chunk.chunkIndex());
        statement.setString(7, chunk.content());
        statement.setString(8, chunk.metadataJson());
        statement.setInt(9, chunk.characterCount());
        statement.setString(10, vectorLiteral(chunk.embedding()));
        if (chunk.embeddingDimensions() == null) {
          statement.setNull(11, java.sql.Types.INTEGER);
        } else {
          statement.setInt(11, chunk.embeddingDimensions());
        }
      }

      @Override
      public int getBatchSize() {
        return chunks.size();
      }
    });
  }

  /** {@inheritDoc} */
  @Override
  public void deleteByDocumentId(final String documentId) {
    jdbcTemplate.update(
        "delete from simpoint_ai_knowledge_chunks where document_id = ?",
        documentId
    );
  }

  /** {@inheritDoc} */
  @Override
  public void deleteByKnowledgeBaseId(final String knowledgeBaseId) {
    jdbcTemplate.update(
        "delete from simpoint_ai_knowledge_chunks where knowledge_base_id = ?",
        knowledgeBaseId
    );
  }

  /** {@inheritDoc} */
  @Override
  public long countByKnowledgeBaseId(final String knowledgeBaseId) {
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from simpoint_ai_knowledge_chunks where knowledge_base_id = ?",
        Long.class,
        knowledgeBaseId
    );
    return count == null ? 0L : count;
  }

  /** {@inheritDoc} */
  @Override
  public List<AiKnowledgeSearchHit> search(final AiKnowledgeSearchSpec spec) {
    String documentFilter = spec.documentIds() == null || spec.documentIds().isEmpty()
        ? ""
        : " and c.document_id in (:documentIds)";
    final String sql = switch (spec.mode()) {
      case VECTOR -> vectorSearchSql(documentFilter);
      case KEYWORD -> keywordSearchSql(documentFilter);
      case HYBRID -> hybridSearchSql(documentFilter);
    };
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("knowledgeBaseId", spec.knowledgeBaseId());
    parameters.put("query", spec.query());
    parameters.put("scoreThreshold", spec.scoreThreshold());
    parameters.put("topK", spec.topK());
    parameters.put("vectorWeight", spec.vectorWeight());
    parameters.put("keywordWeight", spec.keywordWeight());
    parameters.put("rrfK", hybridRrfK);
    parameters.put("candidateLimit", candidateLimit(spec.topK()));
    if (spec.mode() != AiKnowledgeRetrievalMode.KEYWORD) {
      parameters.put("embedding", vectorLiteral(spec.queryEmbedding()));
    }
    if (!documentFilter.isEmpty()) {
      parameters.put("documentIds", spec.documentIds());
    }
    return namedJdbcTemplate.query(sql, parameters, (resultSet, rowNumber) ->
        new AiKnowledgeSearchHit(
            resultSet.getString("id"),
            resultSet.getString("document_id"),
            resultSet.getString("document_name"),
            resultSet.getInt("chunk_index"),
            resultSet.getString("content"),
            resultSet.getDouble("score"),
            resultSet.getDouble("vector_score"),
            resultSet.getDouble("keyword_score"),
            resultSet.getString("metadata_json")
        ));
  }

  private static String vectorSearchSql(final String documentFilter) {
    return """
        with candidates as (
          select c.id, c.document_id, d.name as document_name, c.chunk_index,
                 c.content, c.metadata_json,
                 greatest(0.0, least(1.0,
                   1.0 - (c.embedding <=> cast(:embedding as vector)))) as vector_score
          from simpoint_ai_knowledge_chunks c
          join simpoint_ai_knowledge_documents d on d.id = c.document_id
          where c.knowledge_base_id = :knowledgeBaseId
            and c.embedding is not null
            and d.deleted_at is null
            and d.status = 'READY'
            %s
          order by c.embedding <=> cast(:embedding as vector)
          limit :candidateLimit
        )
        select id, document_id, document_name, chunk_index, content, metadata_json,
               vector_score as score, vector_score, 0.0 as keyword_score
        from candidates
        where vector_score >= :scoreThreshold
        order by score desc, chunk_index asc
        limit :topK
        """.formatted(documentFilter);
  }

  private static String keywordSearchSql(final String documentFilter) {
    return """
        with candidates as (
          select c.id, c.document_id, d.name as document_name, c.chunk_index,
                 c.content, c.metadata_json,
                 least(1.0, greatest(
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('simple', :query)) * 4.0,
                   similarity(c.content, :query)
                 )) as keyword_score
          from simpoint_ai_knowledge_chunks c
          join simpoint_ai_knowledge_documents d on d.id = c.document_id
          where c.knowledge_base_id = :knowledgeBaseId
            and d.deleted_at is null
            and d.status = 'READY'
            %s
          order by keyword_score desc
          limit :candidateLimit
        )
        select id, document_id, document_name, chunk_index, content, metadata_json,
               keyword_score as score, 0.0 as vector_score, keyword_score
        from candidates
        where keyword_score >= :scoreThreshold
        order by score desc, chunk_index asc
        limit :topK
        """.formatted(documentFilter);
  }

  private static String hybridSearchSql(final String documentFilter) {
    return """
        with vector_candidates as (
          select c.id,
                 greatest(0.0, least(1.0,
                   1.0 - (c.embedding <=> cast(:embedding as vector)))) as vector_score
          from simpoint_ai_knowledge_chunks c
          join simpoint_ai_knowledge_documents d on d.id = c.document_id
          where c.knowledge_base_id = :knowledgeBaseId
            and c.embedding is not null
            and d.deleted_at is null
            and d.status = 'READY'
            %s
          order by c.embedding <=> cast(:embedding as vector)
          limit :candidateLimit
        ), vector_ranked as (
          select id, vector_score,
                 row_number() over (order by vector_score desc, id) as vector_rank
          from vector_candidates
        ), keyword_candidates as (
          select c.id,
                 least(1.0, greatest(
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('simple', :query)) * 4.0,
                   similarity(c.content, :query)
                 )) as keyword_score
          from simpoint_ai_knowledge_chunks c
          join simpoint_ai_knowledge_documents d on d.id = c.document_id
          where c.knowledge_base_id = :knowledgeBaseId
            and d.deleted_at is null
            and d.status = 'READY'
            %s
          order by keyword_score desc, c.id
          limit :candidateLimit
        ), keyword_ranked as (
          select id, keyword_score,
                 row_number() over (order by keyword_score desc, id) as keyword_rank
          from keyword_candidates
        ), fused as (
          select coalesce(v.id, k.id) as id,
                 coalesce(v.vector_score, 0.0) as vector_score,
                 coalesce(k.keyword_score, 0.0) as keyword_score,
                 (:vectorWeight * case when v.vector_rank is null then 0.0
                   else (:rrfK + 1.0) / (:rrfK + v.vector_rank) end
                  + :keywordWeight * case when k.keyword_rank is null then 0.0
                   else (:rrfK + 1.0) / (:rrfK + k.keyword_rank) end) as score
          from vector_ranked v
          full outer join keyword_ranked k on k.id = v.id
        )
        select c.id, c.document_id, d.name as document_name, c.chunk_index,
               c.content, c.metadata_json,
               f.score, f.vector_score, f.keyword_score
        from fused f
        join simpoint_ai_knowledge_chunks c on c.id = f.id
        join simpoint_ai_knowledge_documents d on d.id = c.document_id
        where f.score >= :scoreThreshold
        order by f.score desc, c.chunk_index asc
        limit :topK
        """.formatted(documentFilter, documentFilter);
  }

  private int candidateLimit(final int topK) {
    long desired = (long) Math.max(1, topK) * hybridCandidateMultiplier;
    return (int) Math.min(maxRetrievalCandidates, desired);
  }

  private String vectorLiteral(final List<Double> vector) {
    if (vector == null) {
      return null;
    }
    if (vector.isEmpty() || vector.size() > storedVectorDimensions) {
      throw new IllegalArgumentException(
          "Embedding 维度必须在 1 到 " + storedVectorDimensions + " 之间"
      );
    }
    StringBuilder value = new StringBuilder(storedVectorDimensions * 8).append('[');
    for (int index = 0; index < storedVectorDimensions; index++) {
      if (index > 0) {
        value.append(',');
      }
      value.append(index < vector.size() ? vector.get(index) : 0.0D);
    }
    return value.append(']').toString();
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }
}
