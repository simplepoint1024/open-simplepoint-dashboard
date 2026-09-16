package org.simplepoint.plugin.ai.core.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;

class AiApiKeyHasherTest {

  @Test
  void issuesOpaqueKeyAndVerifiesWithoutPersistingRawSecret() {
    AiProperties properties = new AiProperties();
    properties.setApiKeyHashPepper("unit-test-api-key-pepper");
    AiApiKeyHasher hasher = new AiApiKeyHasher(properties);

    AiApiKeyHasher.IssuedSecret issued = hasher.issue();

    assertThat(issued.rawKey()).startsWith(issued.prefix() + ".");
    assertThat(hasher.prefix(issued.rawKey())).isEqualTo(issued.prefix());
    assertThat(issued.hash()).doesNotContain(issued.rawKey());
    assertThat(hasher.matches(issued.rawKey(), issued.hash())).isTrue();
    assertThat(hasher.matches(issued.rawKey() + "x", issued.hash())).isFalse();
  }

  @Test
  void rejectsMissingOrWeakPepperWhenIssuingKey() {
    AiProperties properties = new AiProperties();
    properties.setCredentialEncryptionKey("short");
    AiApiKeyHasher hasher = new AiApiKeyHasher(properties);

    assertThatThrownBy(hasher::issue)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("api-key-hash-pepper");
  }
}
