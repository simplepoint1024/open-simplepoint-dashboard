package org.simplepoint.plugin.ai.mcp.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpSkillCapabilityTokenCodecTest {

  private static final String SIGNING_KEY =
      "test-capability-signing-key-with-at-least-32-bytes";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void roundTripsSignedCapabilityClaims() {
    McpSkillCapabilityClaims claims = claims();

    String token = McpSkillCapabilityTokenCodec.issue(
        objectMapper,
        claims,
        SIGNING_KEY
    );

    assertThat(McpSkillCapabilityTokenCodec.verify(
        objectMapper,
        token,
        SIGNING_KEY
    )).isEqualTo(claims);
  }

  @Test
  void rejectsTamperedPayload() {
    String token = McpSkillCapabilityTokenCodec.issue(
        objectMapper,
        claims(),
        SIGNING_KEY
    );
    String[] parts = token.split("\\.");
    String tampered = parts[0] + "." + parts[1] + "A." + parts[2];

    assertThatThrownBy(() -> McpSkillCapabilityTokenCodec.verify(
        objectMapper,
        tampered,
        SIGNING_KEY
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  void refusesWeakSigningKeys() {
    assertThatThrownBy(() -> McpSkillCapabilityTokenCodec.issue(
        objectMapper,
        claims(),
        "too-short"
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  private static McpSkillCapabilityClaims claims() {
    return new McpSkillCapabilityClaims(
        "simplepoint-ai-control-plane",
        "simplepoint-mcp-gateway",
        "nonce-a",
        100,
        190,
        "tools/call",
        "SYSTEM",
        null,
        "user-a",
        null,
        "skill-a",
        "version-a",
        "execution-a",
        "step-a",
        "server-a",
        "snapshot-a",
        "echo",
        1,
        262144,
        262144
    );
  }
}
