package org.simplepoint.plugin.ai.agent.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.jdbc.core.JdbcTemplate;

class PgAiAgentMemoryRepositoryTest {

  @Test
  void bindsExpirationAsSqlTimestamp() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PgAiAgentMemoryRepository repository =
        new PgAiAgentMemoryRepository(jdbcTemplate);
    Instant expiresAt = Instant.parse("2026-08-30T00:00:00Z");
    AiAgentMemoryWrite memory = new AiAgentMemoryWrite(
        "memory-id",
        "agent-id",
        "version-id",
        "execution-id",
        AiResourceScope.SYSTEM,
        null,
        AgentMemoryScope.SUBJECT,
        "subject-id",
        "content",
        "0".repeat(64),
        expiresAt,
        50
    );

    assertEquals("memory-id", repository.storeAndPrune(memory));

    Object[] insertArguments = jdbcTemplate.argumentSets.getFirst();
    assertEquals(11, insertArguments.length);
    assertNull(insertArguments[5]);
    assertInstanceOf(Timestamp.class, insertArguments[10]);
    assertEquals(Timestamp.from(expiresAt), insertArguments[10]);
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {

    private final List<Object[]> argumentSets = new ArrayList<>();

    @Override
    public int update(final String sql, final Object... arguments) {
      argumentSets.add(arguments);
      return 1;
    }

    @Override
    public int update(final String sql) {
      return 1;
    }

    @Override
    public <T> T queryForObject(
        final String sql,
        final Class<T> requiredType,
        final Object... arguments
    ) {
      return requiredType.cast("memory-id");
    }
  }
}
