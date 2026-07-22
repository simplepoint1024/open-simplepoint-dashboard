package org.simplepoint.plugin.ai.knowledge.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL queue using row leases and {@code SKIP LOCKED} for multi-instance workers. */
@Repository
public class PgKnowledgeIndexJobRepository implements AiKnowledgeIndexJobRepository {

  private static final String TABLE = "simpoint_ai_knowledge_index_jobs";

  private final JdbcTemplate jdbcTemplate;

  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  /** Creates the durable index-job repository. */
  public PgKnowledgeIndexJobRepository(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
  }

  @Override
  public void enqueue(
      final String documentId,
      final String knowledgeBaseId,
      final AiResourceScope scopeType,
      final String tenantId,
      final boolean preserveExistingIndex
  ) {
    jdbcTemplate.update("""
        insert into simpoint_ai_knowledge_index_jobs (
          document_id, knowledge_base_id, scope_type, tenant_id, job_generation,
          attempt_count, preserve_existing_index, status, next_attempt_at,
          created_at, updated_at
        ) values (?, ?, ?, ?, 1, 0, ?, 'PENDING', current_timestamp,
                  current_timestamp, current_timestamp)
        on conflict (document_id) do update set
          knowledge_base_id = excluded.knowledge_base_id,
          scope_type = excluded.scope_type,
          tenant_id = excluded.tenant_id,
          job_generation = simpoint_ai_knowledge_index_jobs.job_generation + 1,
          attempt_count = 0,
          preserve_existing_index = excluded.preserve_existing_index,
          status = 'PENDING',
          next_attempt_at = current_timestamp,
          lease_owner = null,
          lease_until = null,
          last_error = null,
          updated_at = current_timestamp
        """,
        documentId,
        knowledgeBaseId,
        scopeType.name(),
        tenantId,
        preserveExistingIndex
    );
  }

  @Override
  public List<AiKnowledgeIndexJob> claim(
      final String leaseOwner,
      final int limit,
      final Duration leaseDuration
  ) {
    return jdbcTemplate.query("""
        with candidates as (
          select document_id
          from simpoint_ai_knowledge_index_jobs
          where (status = 'PENDING' and next_attempt_at <= current_timestamp)
             or (status = 'PROCESSING' and lease_until < current_timestamp)
          order by next_attempt_at asc, created_at asc
          for update skip locked
          limit ?
        )
        update simpoint_ai_knowledge_index_jobs job
        set status = 'PROCESSING',
            attempt_count = job.attempt_count + 1,
            lease_owner = ?,
            lease_until = current_timestamp + (? * interval '1 second'),
            updated_at = current_timestamp
        from candidates
        where job.document_id = candidates.document_id
        returning job.document_id, job.knowledge_base_id, job.scope_type, job.tenant_id,
                  job.job_generation, job.attempt_count, job.preserve_existing_index,
                  job.lease_owner
        """,
        new IndexJobRowMapper(),
        Math.max(1, limit),
        leaseOwner,
        seconds(leaseDuration)
    );
  }

  @Override
  public boolean renew(final AiKnowledgeIndexJob job, final Duration leaseDuration) {
    return jdbcTemplate.update("""
        update simpoint_ai_knowledge_index_jobs
        set lease_until = current_timestamp + (? * interval '1 second'),
            updated_at = current_timestamp
        where document_id = ? and job_generation = ? and status = 'PROCESSING'
          and lease_owner = ?
        """,
        seconds(leaseDuration),
        job.documentId(),
        job.generation(),
        job.leaseOwner()
    ) == 1;
  }

  @Override
  public boolean isClaimed(final AiKnowledgeIndexJob job) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from simpoint_ai_knowledge_index_jobs
        where document_id = ? and job_generation = ? and status = 'PROCESSING'
          and lease_owner = ?
        """,
        Integer.class,
        job.documentId(),
        job.generation(),
        job.leaseOwner()
    );
    return count != null && count == 1;
  }

  @Override
  public boolean complete(final AiKnowledgeIndexJob job) {
    return jdbcTemplate.update("""
        delete from simpoint_ai_knowledge_index_jobs
        where document_id = ? and job_generation = ? and status = 'PROCESSING'
          and lease_owner = ?
        """,
        job.documentId(),
        job.generation(),
        job.leaseOwner()
    ) == 1;
  }

  @Override
  public boolean fail(
      final AiKnowledgeIndexJob job,
      final boolean terminal,
      final Duration retryDelay,
      final String errorMessage
  ) {
    String status = terminal ? "FAILED" : "PENDING";
    long retrySeconds = terminal ? 0L : seconds(retryDelay);
    return jdbcTemplate.update("""
        update simpoint_ai_knowledge_index_jobs
        set status = ?,
            next_attempt_at = current_timestamp + (? * interval '1 second'),
            lease_owner = null,
            lease_until = null,
            last_error = ?,
            updated_at = current_timestamp
        where document_id = ? and job_generation = ? and status = 'PROCESSING'
          and lease_owner = ?
        """,
        status,
        retrySeconds,
        truncate(errorMessage),
        job.documentId(),
        job.generation(),
        job.leaseOwner()
    ) == 1;
  }

  @Override
  public void release(final AiKnowledgeIndexJob job) {
    jdbcTemplate.update("""
        update simpoint_ai_knowledge_index_jobs
        set status = 'PENDING',
            attempt_count = greatest(0, attempt_count - 1),
            next_attempt_at = current_timestamp,
            lease_owner = null,
            lease_until = null,
            updated_at = current_timestamp
        where document_id = ? and job_generation = ? and status = 'PROCESSING'
          and lease_owner = ?
        """,
        job.documentId(),
        job.generation(),
        job.leaseOwner()
    );
  }

  @Override
  public void deleteByDocumentIds(final Collection<String> documentIds) {
    if (documentIds == null || documentIds.isEmpty()) {
      return;
    }
    namedJdbcTemplate.update(
        "delete from " + TABLE + " where document_id in (:documentIds)",
        Map.of("documentIds", documentIds)
    );
  }

  @Override
  public void deleteByKnowledgeBaseId(final String knowledgeBaseId) {
    jdbcTemplate.update(
        "delete from " + TABLE + " where knowledge_base_id = ?",
        knowledgeBaseId
    );
  }

  private static long seconds(final Duration duration) {
    return Math.max(1L, duration == null ? 1L : duration.toSeconds());
  }

  private static String truncate(final String message) {
    if (message == null || message.isBlank()) {
      return "未知错误";
    }
    return message.length() <= 2000 ? message : message.substring(0, 2000);
  }

  private static final class IndexJobRowMapper implements RowMapper<AiKnowledgeIndexJob> {

    @Override
    public AiKnowledgeIndexJob mapRow(final ResultSet resultSet, final int rowNumber)
        throws SQLException {
      return new AiKnowledgeIndexJob(
          resultSet.getString("document_id"),
          resultSet.getString("knowledge_base_id"),
          AiResourceScope.valueOf(resultSet.getString("scope_type")),
          resultSet.getString("tenant_id"),
          resultSet.getLong("job_generation"),
          resultSet.getInt("attempt_count"),
          resultSet.getBoolean("preserve_existing_index"),
          resultSet.getString("lease_owner")
      );
    }
  }
}
