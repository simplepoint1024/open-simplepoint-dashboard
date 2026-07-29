package org.simplepoint.plugin.ai.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisManagedMcpSessionDirectoryTest {

  private final AiRuntimeProperties properties = new AiRuntimeProperties();

  private final RedisManagedMcpSessionDirectory directory =
      new RedisManagedMcpSessionDirectory(
          mock(StringRedisTemplate.class),
          properties
      );

  @Test
  void hashesTheExternalSessionIdInsteadOfPuttingItInTheRedisKey() {
    String rawSessionId = "private-session-id-that-must-not-be-persisted";

    String key = directory.sessionKey(
        "server-1",
        "TENANT",
        "tenant-1",
        rawSessionId
    );

    assertThat(key)
        .startsWith("simplepoint:ai:runtime:mcp:sessions:assignments:")
        .doesNotContain(rawSessionId)
        .hasSize(
            "simplepoint:ai:runtime:mcp:sessions:assignments:".length() + 64
        );
  }

  @Test
  void rejectsOversizedSessionIdsBeforeAccessingRedis() {
    properties.setMcpSessionIdMaxLength(32);

    assertThatThrownBy(() -> directory.sessionKey(
        "server-1",
        "SYSTEM",
        null,
        "x".repeat(33)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long");
  }
}
