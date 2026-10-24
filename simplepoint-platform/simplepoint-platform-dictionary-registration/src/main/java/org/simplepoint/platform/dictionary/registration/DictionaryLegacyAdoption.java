package org.simplepoint.platform.dictionary.registration;

import java.util.Objects;
import java.util.UUID;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;

/** Trusted local installer contracts. These are not part of the runtime registration RPC. */
public final class DictionaryLegacyAdoption {
  private DictionaryLegacyAdoption() {}

  /** Read-only, bounded summary; hashes bind the declaration and every retained storage value. */
  public record Preview(
      String declarationFingerprint, String dataFingerprint, int dictionaries, int items) {
    /** Rejects incomplete or oversized previews. */
    public Preview {
      hash(declarationFingerprint);
      hash(dataFingerprint);
      if (dictionaries < 1 || dictionaries > 64 || items < 0 || items > 8192) {
        throw new IllegalArgumentException("Invalid dictionary adoption preview");
      }
    }
  }

  /** Installer-translated durable bootstrap transfer receipt; never caller authentication. */
  public record LegacyCompletion(
      UUID operationId,
      String targetScope,
      String moduleCode,
      String contributionKey,
      String version,
      String checksum,
      String sourceFingerprint) {
    /** Requires an explicit retired legacy dictionary scope. */
    public LegacyCompletion {
      Objects.requireNonNull(operationId);
      if (!"owner/identity/dictionary".equals(targetScope)) {
        throw new IllegalArgumentException("Invalid dictionary adoption target");
      }
      text(moduleCode, 128);
      text(contributionKey, 128);
      text(version, 64);
      text(checksum, 256);
      hash(sourceFingerprint);
    }
  }

  /** Exact legacy revision, reviewed data and declaration; no instruction to rewrite data. */
  public record Command(
      DictionaryContribution declaration, Preview expected, LegacyCompletion completion) {
    /** A changed declaration requires an ordinary later upgrade, not silent adoption. */
    public Command {
      Objects.requireNonNull(declaration);
      Objects.requireNonNull(expected);
      Objects.requireNonNull(completion);
      if (declaration.dictionaries().size() != expected.dictionaries()
          || !DictionaryFingerprint.of(declaration).equals(expected.declarationFingerprint())
          || !declaration.moduleCode().equals(completion.moduleCode())
          || !declaration.contributionKey().equals(completion.contributionKey())
          || !declaration.version().equals(completion.version())
          || !(declaration.moduleCode()
                  + ":dictionary:"
                  + declaration.contributionKey()
                  + ":"
                  + declaration.version())
              .equals(completion.checksum())) {
        throw new IllegalArgumentException("Legacy dictionary revision does not match declaration");
      }
    }
  }

  /** Completion means ownership adopted; deployment approval and runtime readiness are separate. */
  public record Receipt(
      UUID operationId,
      String moduleCode,
      String declarationFingerprint,
      String sourceFingerprint,
      String dataFingerprint,
      int dictionaries,
      int items) {}

  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid dictionary adoption fingerprint");
    }
  }

  private static void text(String value, int bound) {
    if (value == null
        || value.isBlank()
        || value.length() > bound
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid legacy dictionary completion");
    }
  }
}
