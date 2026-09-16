package org.simplepoint.plugin.ai.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisManagedMcpSessionDirectoryTest {

  private final AiRuntimeProperties properties = new AiRuntimeProperties();

  private final StringRedisTemplate redisTemplate = mock(
      StringRedisTemplate.class
  );

  private final RedisManagedMcpSessionDirectory directory =
      new RedisManagedMcpSessionDirectory(
          redisTemplate,
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

  @Test
  void reclaimsTheOpaqueCapacityIndexForFencedWorkload() {
    directory.invalidateWorkload(new ManagedMcpSessionDirectory.Assignment(
        "workload-private-id",
        "lease-private-id",
        7
    ));

    verify(redisTemplate).execute(
        any(),
        argThat(keys -> keys.size() == 1
            && keys.getFirst().startsWith(
                "simplepoint:ai:runtime:mcp:sessions:capacity:"
            )
            && !keys.getFirst().contains("workload-private-id")
            && !keys.getFirst().contains("lease-private-id")),
        any(Object[].class)
    );
  }
}
