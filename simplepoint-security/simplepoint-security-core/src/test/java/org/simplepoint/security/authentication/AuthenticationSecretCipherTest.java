package org.simplepoint.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthenticationSecretCipherTest {
  @TempDir Path directory;
  private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private AuthenticationSecretCipher cipher(String active, boolean includeOld) throws Exception {
    Path path = directory.resolve(active + ".json");
    String old = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) 1);
    String next = Base64.getEncoder().encodeToString(bytes);
    Files.writeString(
        path,
        "{\"activeKeyId\":\""
            + active
            + "\",\"keys\":{"
            + (includeOld ? "\"old\":\"" + old + "\"," : "")
            + "\"next\":\""
            + next
            + "\"}}");
    return new AuthenticationSecretCipher(path.toString());
  }

  @Test
  void randomizesCiphertextAndAuthenticatesOwnerAndContents() throws Exception {
    var cipher = cipher("old", true);
    String stored = cipher.encrypt("alice", SECRET);
    assertThat(stored).doesNotContain(SECRET).isNotEqualTo(cipher.encrypt("alice", SECRET));
    assertThat(cipher.decrypt("alice", stored)).isEqualTo(SECRET);
    assertThatThrownBy(() -> cipher.decrypt("bob", stored))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> cipher.decrypt("alice", stored.substring(0, stored.length() - 4) + "AAAA"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt("alice", SECRET))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rotationRetainsOldReadKeyAndMigrationReencrypts() throws Exception {
    var old = cipher("old", true);
    var rotated = cipher("next", true);
    String previous = old.encrypt("alice", SECRET);
    assertThat(rotated.decrypt("alice", previous)).isEqualTo(SECRET);
    String migrated = rotated.migrate("alice", previous);
    assertThat(migrated).startsWith("totp:v1:next:");
    assertThat(cipher("next", false).decrypt("alice", migrated)).isEqualTo(SECRET);
    assertThatThrownBy(() -> cipher("next", false).decrypt("alice", previous))
        .isInstanceOf(IllegalStateException.class);
    assertThat(rotated.decrypt("alice", rotated.migrate("alice", SECRET))).isEqualTo(SECRET);
  }

  @Test
  void missingOrInvalidKeyNeverFallsBackToPlaintext() throws Exception {
    assertThatThrownBy(() -> new AuthenticationSecretCipher("").encrypt("alice", SECRET))
        .isInstanceOf(IllegalStateException.class);
    Path path = directory.resolve("bad.json");
    Files.writeString(path, "credential-do-not-print");
    assertThatThrownBy(() -> new AuthenticationSecretCipher(path.toString()))
        .hasMessage("Cannot load authentication key ring")
        .hasNoCause();
  }
}
