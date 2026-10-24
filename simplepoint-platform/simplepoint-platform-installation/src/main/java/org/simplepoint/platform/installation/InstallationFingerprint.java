package org.simplepoint.platform.installation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned unambiguous fingerprints of non-secret immutable installation inputs. */
public final class InstallationFingerprint {
  private InstallationFingerprint() {}

  /** Caller supplies canonical field order; null and empty remain different. */
  public static String of(String... values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update((byte) 1);
      for (String value : values) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
          digest.update(bytes);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** Fingerprints are identifiers, never arbitrary diagnostic or payload text. */
  public static String require(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid installation fingerprint");
    }
    return value;
  }
}
