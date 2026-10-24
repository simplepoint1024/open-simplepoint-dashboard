package org.simplepoint.platform.dictionary.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Command;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.LegacyCompletion;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Preview;

class DictionaryLegacyAdoptionTest {
  @Test
  void legacyCompletionMustMatchItsActualRevisionAndRetiredTarget() {
    var declaration = declaration("1");
    var preview = new Preview(DictionaryFingerprint.of(declaration), "a".repeat(64), 1, 0);
    var valid = completion("1", "example:dictionary:seed:1");
    assertEquals(declaration, new Command(declaration, preview, valid).declaration());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command(declaration, preview, completion("2", "example:dictionary:seed:2")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command(declaration, preview, completion("1", "different-checksum")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyCompletion(
                UUID.randomUUID(),
                "module/example",
                "example",
                "seed",
                "1",
                valid.checksum(),
                "b".repeat(64)));
  }

  @Test
  void reviewCannotBeReusedForDifferentDeclarationOrExceedStorageBounds() {
    var preview = new Preview(DictionaryFingerprint.of(declaration("1")), "a".repeat(64), 1, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command(declaration("2"), preview, completion("2", "example:dictionary:seed:2")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Preview(preview.declarationFingerprint(), preview.dataFingerprint(), 1, 8193));
  }

  private DictionaryContribution declaration(String version) {
    return new DictionaryContribution(
        "example",
        "seed",
        version,
        100,
        List.of(new DictionaryDeclaration("example.code", "Name", null, 10, List.of())));
  }

  private LegacyCompletion completion(String version, String checksum) {
    return new LegacyCompletion(
        UUID.randomUUID(),
        "owner/identity/dictionary",
        "example",
        "seed",
        version,
        checksum,
        "b".repeat(64));
  }
}
