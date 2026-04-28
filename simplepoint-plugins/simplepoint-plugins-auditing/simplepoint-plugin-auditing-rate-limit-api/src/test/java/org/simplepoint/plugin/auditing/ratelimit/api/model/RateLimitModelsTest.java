package org.simplepoint.plugin.auditing.ratelimit.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimitModelsTest {

  // ---- RateLimitRedisKeys ----

  @Test
  void rateLimitRedisKeys_constants() {
    assertThat(RateLimitRedisKeys.SERVICE_RULES_KEY)
        .isEqualTo("simplepoint:gateway:rate-limit:service-rules");
    assertThat(RateLimitRedisKeys.ENDPOINT_RULES_KEY)
        .isEqualTo("simplepoint:gateway:rate-limit:endpoint-rules");
  }

  // ---- RateLimitKeyStrategy ----

  @Test
  void rateLimitKeyStrategy_normalize_nullOrBlankDefaultsToClientIp() {
    assertThat(RateLimitKeyStrategy.normalize(null)).isEqualTo("CLIENT_IP");
    assertThat(RateLimitKeyStrategy.normalize("")).isEqualTo("CLIENT_IP");
    assertThat(RateLimitKeyStrategy.normalize("  ")).isEqualTo("CLIENT_IP");
  }

  @Test
  void rateLimitKeyStrategy_normalize_caseInsensitive() {
    assertThat(RateLimitKeyStrategy.normalize("global")).isEqualTo("GLOBAL");
    assertThat(RateLimitKeyStrategy.normalize("USER_ID")).isEqualTo("USER_ID");
    assertThat(RateLimitKeyStrategy.normalize("tenant_id")).isEqualTo("TENANT_ID");
  }

  @Test
  void rateLimitKeyStrategy_normalize_unknownValue_throwsIllegalArgument() {
    assertThatThrownBy(() -> RateLimitKeyStrategy.normalize("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rateLimitKeyStrategy_enumValues() {
    assertThat(RateLimitKeyStrategy.values())
        .containsExactlyInAnyOrder(
            RateLimitKeyStrategy.GLOBAL,
            RateLimitKeyStrategy.CLIENT_IP,
            RateLimitKeyStrategy.USER_ID,
            RateLimitKeyStrategy.TENANT_ID);
  }
}
