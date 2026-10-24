package org.simplepoint.platform.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DictionaryFingerprintTest {
  @Test
  void contentIdentityPreservesNullsUnicodeAndListOrder() {
    var original = declaration(null, 10, List.of(item("a"), item("b")));
    assertEquals(
        DictionaryFingerprint.of(original),
        DictionaryFingerprint.of(declaration(null, 10, List.of(item("a"), item("b")))));
    for (var changed :
        List.of(
            declaration("", 10, original.dictionaries().getFirst().items()),
            declaration(null, 11, original.dictionaries().getFirst().items()),
            declaration(null, 10, List.of(item("b"), item("a"))),
            declaration("漢字😀", 10, original.dictionaries().getFirst().items()))) {
      assertNotEquals(DictionaryFingerprint.of(original), DictionaryFingerprint.of(changed));
    }
  }

  private DictionaryContribution declaration(
      String description, int order, List<DictionaryItemDeclaration> items) {
    return new DictionaryContribution(
        "example",
        "seed",
        "1",
        order,
        List.of(new DictionaryDeclaration("code", "名称", description, 10, items)));
  }

  private DictionaryItemDeclaration item(String value) {
    return new DictionaryItemDeclaration(value, "名称😀", null, null, 10);
  }
}
