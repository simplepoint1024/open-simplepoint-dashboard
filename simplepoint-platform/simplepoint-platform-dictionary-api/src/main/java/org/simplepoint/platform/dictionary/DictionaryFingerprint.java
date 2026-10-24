package org.simplepoint.platform.dictionary;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned canonical content identity; independent of JSON configuration and deployment hosts. */
public final class DictionaryFingerprint {
  private DictionaryFingerprint() {}

  /**
   * Includes every declaration field, optional absence and list order without delimiter ambiguity.
   */
  public static String of(DictionaryContribution declaration) {
    java.util.Objects.requireNonNull(declaration, "declaration");
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeInt(1);
      text(out, declaration.moduleCode());
      text(out, declaration.contributionKey());
      text(out, declaration.version());
      out.writeInt(declaration.order());
      out.writeInt(declaration.dictionaries().size());
      for (var dictionary : declaration.dictionaries()) {
        text(out, dictionary.code());
        text(out, dictionary.name());
        text(out, dictionary.description());
        out.writeInt(dictionary.sort());
        out.writeInt(dictionary.items().size());
        for (var item : dictionary.items()) {
          text(out, item.value());
          text(out, item.name());
          text(out, item.i18nKey());
          text(out, item.description());
          out.writeInt(item.sort());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Dictionary fingerprint unavailable", impossible);
    }
  }

  private static void text(DataOutputStream out, String text) throws IOException {
    if (text == null) {
      out.writeInt(-1);
      return;
    }
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }
}
