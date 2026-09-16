package org.simplepoint.plugin.ai.runtime.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiRuntimeImageCacheCodecTest {

  private final AiRuntimeImageCacheCodec codec =
      new AiRuntimeImageCacheCodec(new ObjectMapper());

  @Test
  void shouldCanonicalizeAndExtendDigestSnapshot() {
    String first = "sha256:" + "a".repeat(64);
    String second = "sha256:" + "b".repeat(64);

    String encoded = codec.encode(List.of(second, first, second));

    assertEquals(List.of(first, second), codec.decode(encoded));
    assertTrue(codec.contains(codec.add("[]", first), first));
  }

  @Test
  void shouldRejectMutableOrMalformedIdentifiers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encode(List.of("registry.example/tool:latest"))
    );
  }
}
