package org.simplepoint.plugin.ai.core.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;

class AiCredentialCipherTest {

  @Test
  void shouldEncryptAndDecryptCredential() {
    AiProperties properties = new AiProperties();
    properties.setCredentialEncryptionKey("test-master-key");
    AiCredentialCipher cipher = new AiCredentialCipher(properties);

    String encrypted = cipher.encrypt("sk-test-value");

    assertNotEquals("sk-test-value", encrypted);
    assertEquals("sk-test-value", cipher.decrypt(encrypted));
  }

  @Test
  void shouldRejectMissingMasterKey() {
    AiCredentialCipher cipher = new AiCredentialCipher(new AiProperties());

    assertThrows(IllegalStateException.class, () -> cipher.encrypt("sk-test-value"));
  }
}
