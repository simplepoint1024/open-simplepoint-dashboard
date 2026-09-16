package org.simplepoint.plugin.ai.runtime.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiRuntimeEgressPolicyCodecTest {

  private final AiRuntimeEgressPolicyCodec codec =
      new AiRuntimeEgressPolicyCodec(new ObjectMapper());

  @Test
  void shouldCanonicalizePolicyBoundDnsHosts() {
    String json = codec.normalize(
        "egress",
        List.of("API.EXAMPLE.NET.", "*.files.example.com")
    );

    assertEquals(
        "[\"*.files.example.com\",\"api.example.net\"]",
        json
    );
    assertEquals(
        List.of("*.files.example.com", "api.example.net"),
        codec.decode("egress", json)
    );
  }

  @Test
  void shouldRejectHostsWithoutEgressModeAndUnsafeDestinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.normalize("none", List.of("api.example.com"))
    );
    for (String host : List.of(
        "127.0.0.1",
        "*.com",
        "api.example.com:443",
        "api..example.com"
    )) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.normalize("egress", List.of(host))
      );
    }
  }

  @Test
  void shouldCanonicalizeExactTcpAndInternalEndpoints() {
    for (String mode : List.of("tcp-egress", "internal-service")) {
      String json = codec.normalize(
          mode,
          List.of("POSTGRES:5432", "db.example.com:6432")
      );
      assertEquals(
          "[\"db.example.com:6432\",\"postgres:5432\"]",
          json
      );
      assertEquals(
          List.of("db.example.com:6432", "postgres:5432"),
          codec.decode(mode, json)
      );
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.normalize("internal-service", List.of("*.db:5432"))
    );
  }
}
