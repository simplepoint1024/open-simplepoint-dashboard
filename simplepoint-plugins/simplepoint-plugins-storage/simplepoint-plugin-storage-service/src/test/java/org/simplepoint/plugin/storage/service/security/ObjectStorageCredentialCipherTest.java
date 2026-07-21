package org.simplepoint.plugin.storage.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;

class ObjectStorageCredentialCipherTest {

  @Test
  void encryptAndDecryptRoundTrip() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    properties.setCredentialEncryptionKey("storage-test-key");
    ObjectStorageCredentialCipher cipher = new ObjectStorageCredentialCipher(properties);

    String ciphertext = cipher.encrypt("secret-value");

    assertNotEquals("secret-value", ciphertext);
    assertEquals("secret-value", cipher.decrypt(ciphertext));
  }

  @Test
  void missingEncryptionKeyIsRejected() {
    ObjectStorageCredentialCipher cipher = new ObjectStorageCredentialCipher(
        new ObjectStorageProperties()
    );

    assertThrows(IllegalStateException.class, () -> cipher.encrypt("secret-value"));
  }
}
