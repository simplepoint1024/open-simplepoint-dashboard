package org.simplepoint.plugin.oidc.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.oidc.api.properties.ExternalIdentityProviderProperties;

class ExternalIdentityProviderCredentialCipherTest {

  @Test
  void encryptsWithRandomAuthenticatedCiphertextAndDecrypts() {
    ExternalIdentityProviderCredentialCipher cipher = cipher("master-key-a");

    String first = cipher.encrypt("client-secret");
    String second = cipher.encrypt("client-secret");

    assertThat(first).startsWith("v1:");
    assertThat(second).startsWith("v1:").isNotEqualTo(first);
    assertThat(cipher.decrypt(first)).isEqualTo("client-secret");
    assertThat(first).doesNotContain("client-secret");
  }

  @Test
  void rejectsCiphertextWhenMasterKeyDoesNotMatch() {
    String ciphertext = cipher("master-key-a").encrypt("client-secret");

    assertThatThrownBy(() -> cipher("master-key-b").decrypt(ciphertext))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("主密钥");
  }

  @Test
  void rejectsMissingMasterKey() {
    assertThatThrownBy(() -> cipher(" ").encrypt("client-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("credential-encryption-key");
  }

  private ExternalIdentityProviderCredentialCipher cipher(final String key) {
    ExternalIdentityProviderProperties properties =
        new ExternalIdentityProviderProperties();
    properties.setCredentialEncryptionKey(key);
    return new ExternalIdentityProviderCredentialCipher(properties);
  }
}
