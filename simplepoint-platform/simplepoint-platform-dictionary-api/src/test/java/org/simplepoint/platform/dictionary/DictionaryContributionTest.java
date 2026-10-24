package org.simplepoint.platform.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DictionaryContributionTest {
  @Test
  void freezesNestedListsAndPreservesOrderAndOptionalMetadata() throws Exception {
    var items = new ArrayList<>(List.of(item("second"), item("first")));
    var dictionaries = new ArrayList<>(List.of(dictionary("example", items)));
    var contribution = contribution(dictionaries);
    items.clear();
    dictionaries.clear();
    assertEquals(
        List.of("second", "first"),
        contribution.dictionaries().getFirst().items().stream()
            .map(DictionaryItemDeclaration::value)
            .toList());
    assertThrows(UnsupportedOperationException.class, () -> contribution.dictionaries().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> contribution.dictionaries().getFirst().items().clear());
    var mapper = new ObjectMapper();
    assertEquals(
        contribution,
        mapper.readValue(mapper.writeValueAsBytes(contribution), DictionaryContribution.class));
    assertNull(contribution.dictionaries().getFirst().description());
  }

  @Test
  void unicodeLengthsCountCodePointsAndRejectMalformedValues() {
    assertEquals(256, item("😀".repeat(128)).value().length());
    assertThrows(IllegalArgumentException.class, () -> item("😀".repeat(129)));
    for (String invalid :
        List.of(
            " ",
            " value",
            "value ",
            "nul" + (char) 0,
            String.valueOf((char) 0xd800),
            String.valueOf((char) 0xdc00))) {
      assertThrows(IllegalArgumentException.class, () -> item(invalid));
    }
    assertEquals("漢字日本語", item("漢字日本語").value());
  }

  @Test
  void rejectsDuplicateCodesAndValuesBeforeApplication() {
    assertThrows(
        IllegalArgumentException.class,
        () -> dictionary("example", List.of(item("same"), item("same"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> contribution(List.of(dictionary("same", List.of()), dictionary("same", List.of()))));
  }

  @Test
  void boundsCollectionsAndEntireBatch() {
    assertThrows(IllegalArgumentException.class, () -> contribution(List.of()));
    assertThrows(IllegalArgumentException.class, () -> contribution(null));
    assertThrows(IllegalArgumentException.class, () -> dictionary("example", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> dictionary("example", java.util.Arrays.asList(item("ok"), null)));
    var thousand = IntStream.range(0, 1024).mapToObj(i -> item("v" + i)).toList();
    assertEquals(1024, dictionary("example", thousand).items().size());
    var oversized = new ArrayList<>(thousand);
    oversized.add(item("excess"));
    assertThrows(IllegalArgumentException.class, () -> dictionary("example", oversized));
    assertEquals(
        4,
        contribution(IntStream.range(0, 4).mapToObj(i -> dictionary("code" + i, thousand)).toList())
            .dictionaries()
            .size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            contribution(
                IntStream.range(0, 5).mapToObj(i -> dictionary("code" + i, thousand)).toList()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            contribution(
                IntStream.range(0, 65).mapToObj(i -> dictionary("code" + i, List.of())).toList()));
  }

  @Test
  void rejectsInvalidIdentifiersWithoutEchoingInput() {
    for (String invalid : List.of("../escape", "a b", "a/child", "x".repeat(129))) {
      var error =
          assertThrows(IllegalArgumentException.class, () -> dictionary(invalid, List.of()));
      assertEquals("INVALID_DICTIONARY_DECLARATION: dictionary.code", error.getMessage());
    }
  }

  @Test
  void validatesAllDisplayMetadataLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DictionaryItemDeclaration("value", " ", null, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DictionaryItemDeclaration("value", "name", "x".repeat(256), null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DictionaryItemDeclaration("value", "name", null, "x".repeat(513), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DictionaryDeclaration("code", "x".repeat(129), null, 0, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DictionaryDeclaration("code", "name", "x".repeat(513), 0, List.of()));
  }

  private static DictionaryItemDeclaration item(String value) {
    return new DictionaryItemDeclaration(value, "名称", null, null, 10);
  }

  private static DictionaryDeclaration dictionary(
      String code, List<DictionaryItemDeclaration> items) {
    return new DictionaryDeclaration(code, "名称", null, 20, items);
  }

  private static DictionaryContribution contribution(List<DictionaryDeclaration> dictionaries) {
    return new DictionaryContribution("example", "initial-dictionaries", "1", 300, dictionaries);
  }
}
