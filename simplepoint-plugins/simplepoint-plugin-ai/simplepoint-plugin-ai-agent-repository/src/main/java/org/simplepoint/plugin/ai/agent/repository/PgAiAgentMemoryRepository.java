package org.simplepoint.plugin.ai.agent.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentMemoryRepository;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemory;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemorySearchSpec;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL full-text and trigram Agent memory repository.
 */
@Repository
public class PgAiAgentMemoryRepository implements AiAgentMemoryRepository {

  private static final String ACTIVE_BOUNDARY = """
      agent_id = ?
      and scope_type = ?
      and tenant_id is not distinct from ?
      and memory_scope = ?
      and subject_id = ?
      and deleted_at is null
      """;

  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates the PostgreSQL Agent memory repository.
   */
  public PgAiAgentMemoryRepository(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<AiAgentMemory> search(final AiAgentMemorySearchSpec spec) {
    String sql = """
        with ranked as (
          select memory.*,
                 least(1.0, greatest(
                   ts_rank_cd(
                     memory.content_tsv,
                     websearch_to_tsquery('simple', ?)
                   ) * 4.0,
                   similarity(memory.content, ?)
                 )) as relevance
          from simpoint_ai_agent_memories memory
          where %s
            and memory.source_execution_id <> ?
            and memory.expires_at > current_timestamp
        )
        select *
        from ranked
        where relevance >= ?
        order by relevance desc, created_at desc, id
        limit ?
        """.formatted(ACTIVE_BOUNDARY);
    return jdbcTemplate.query(
        sql,
        PgAiAgentMemoryRepository::map,
        spec.query(),
        spec.query(),
        spec.agentId(),
        spec.scopeType().name(),
        spec.tenantId(),
        spec.memoryScope().name(),
        spec.subjectId(),
        spec.excludedExecutionId(),
        spec.scoreThreshold(),
        spec.topK()
    );
  }

  @Override
  public List<AiAgentMemory> findRecent(
      final String agentId,
      final AiResourceScope scopeType,
      final String tenantId,
      final AgentMemoryScope memoryScope,
      final String subjectId,
      final int limit
  ) {
    String sql = """
        select memory.*, null::double precision as relevance
        from simpoint_ai_agent_memories memory
        where %s
          and memory.expires_at > current_timestamp
        order by memory.created_at desc, memory.id
        limit ?
        """.formatted(ACTIVE_BOUNDARY);
    return jdbcTemplate.query(
        sql,
        PgAiAgentMemoryRepository::map,
        agentId,
        scopeType.name(),
        tenantId,
        memoryScope.name(),
        subjectId,
        limit
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String storeAndPrune(final AiAgentMemoryWrite memory) {
    jdbcTemplate.update("""
        insert into simpoint_ai_agent_memories (
          id, agent_id, agent_version_id, source_execution_id,
          scope_type, tenant_id, memory_scope, subject_id,
          content, content_hash, created_at, expires_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
        on conflict (source_execution_id) where deleted_at is null
        do nothing
        """,
        memory.id(),
        memory.agentId(),
        memory.agentVersionId(),
        memory.sourceExecutionId(),
        memory.scopeType().name(),
        memory.tenantId(),
        memory.memoryScope().name(),
        memory.subjectId(),
        memory.content(),
        memory.contentHash(),
        Timestamp.from(memory.expiresAt())
    );
    String id = jdbcTemplate.queryForObject("""
        select id
        from simpoint_ai_agent_memories
        where source_execution_id = ?
          and deleted_at is null
        """,
        String.class,
        memory.sourceExecutionId()
    );
    prune(memory);
    return id;
  }

  @Override
  public Optional<AiAgentMemory> findOwned(
      final String memoryId,
      final String agentId,
      final AiResourceScope scopeType,
      final String tenantId,
      final AgentMemoryScope memoryScope,
      final String subjectId
  ) {
    String sql = """
        select memory.*, null::double precision as relevance
        from simpoint_ai_agent_memories memory
        where memory.id = ?
          and %s
        """.formatted(ACTIVE_BOUNDARY);
    return jdbcTemplate.query(
        sql,
        PgAiAgentMemoryRepository::map,
        memoryId,
        agentId,
        scopeType.name(),
        tenantId,
        memoryScope.name(),
        subjectId
    ).stream().findFirst();
  }

  @Override
  public boolean deleteOwned(
      final String memoryId,
      final String agentId,
      final AiResourceScope scopeType,
      final String tenantId,
      final AgentMemoryScope memoryScope,
      final String subjectId
  ) {
    String sql = """
        delete from simpoint_ai_agent_memories
        where id = ?
          and %s
        """.formatted(ACTIVE_BOUNDARY);
    return jdbcTemplate.update(
        sql,
        memoryId,
        agentId,
        scopeType.name(),
        tenantId,
        memoryScope.name(),
        subjectId
    ) > 0;
  }

  private void prune(final AiAgentMemoryWrite memory) {
    jdbcTemplate.update("""
        delete from simpoint_ai_agent_memories
        where expires_at <= current_timestamp
        """);
    String sql = """
        delete from simpoint_ai_agent_memories
        where id in (
          select id
          from simpoint_ai_agent_memories
          where %s
          order by created_at desc, id
          offset ?
        )
        """.formatted(ACTIVE_BOUNDARY);
    jdbcTemplate.update(
        sql,
        memory.agentId(),
        memory.scopeType().name(),
        memory.tenantId(),
        memory.memoryScope().name(),
        memory.subjectId(),
        memory.maximumEntries()
    );
  }

  private static AiAgentMemory map(
      final ResultSet resultSet,
      final int rowNumber
  ) throws SQLException {
    double relevance = resultSet.getDouble("relevance");
    boolean relevanceMissing = resultSet.wasNull();
    return new AiAgentMemory(
        resultSet.getString("id"),
        resultSet.getString("agent_id"),
        resultSet.getString("agent_version_id"),
        resultSet.getString("source_execution_id"),
        AiResourceScope.valueOf(resultSet.getString("scope_type")),
        resultSet.getString("tenant_id"),
        AgentMemoryScope.valueOf(resultSet.getString("memory_scope")),
        resultSet.getString("subject_id"),
        resultSet.getString("content"),
        resultSet.getString("content_hash"),
        relevanceMissing ? null : relevance,
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("expires_at").toInstant()
    );
  }
}
