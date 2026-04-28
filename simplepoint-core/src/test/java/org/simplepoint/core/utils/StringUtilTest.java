package org.simplepoint.core.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StringUtilTest {

  // -------- splitLast --------

  @Test
  void splitLast_noDelimiter_returnsSingleElementArray() {
    String[] result = StringUtil.splitLast("hello", ",");
    assertThat(result).containsExactly("hello");
  }

  @Test
  void splitLast_singleOccurrence_splitIntoTwo() {
    String[] result = StringUtil.splitLast("hello,world", ",");
    assertThat(result).containsExactly("hello", "world");
  }

  @Test
  void splitLast_multipleOccurrences_splitsAtLastDelimiter() {
    String[] result = StringUtil.splitLast("a,b,c", ",");
    assertThat(result).containsExactly("a,b", "c");
  }

  @Test
  void splitLast_delimiterAtEnd_producesEmptyLastPart() {
    String[] result = StringUtil.splitLast("hello,", ",");
    assertThat(result).containsExactly("hello", "");
  }

  @Test
  void splitLast_delimiterAtStart_producesEmptyFirstPart() {
    String[] result = StringUtil.splitLast(",world", ",");
    assertThat(result).containsExactly("", "world");
  }

  @Test
  void splitLast_emptyString_noDelimiter_returnsSingleEmptyElement() {
    String[] result = StringUtil.splitLast("", ",");
    assertThat(result).containsExactly("");
  }

  @Test
  void splitLast_multiCharDelimiter_splitsAfterOneChar() {
    // splitLast uses lastIndex + 1 (not + delimiter.length()), so the tail
    // includes all but the first character of the delimiter
    String[] result = StringUtil.splitLast("key::last::value", "::");
    assertThat(result).containsExactly("key::last", ":value");
  }

  // -------- stringToLongSet --------

  @Test
  void stringToLongSet_singleValue_returnsSetWithOneElement() {
    Set<Long> result = StringUtil.stringToLongSet("42");
    assertThat(result).containsExactly(42L);
  }

  @Test
  void stringToLongSet_twoValues_returnsSetWithBothElements() {
    Set<Long> result = StringUtil.stringToLongSet("1,2");
    assertThat(result).containsExactlyInAnyOrder(1L, 2L);
  }

  @Test
  void stringToLongSet_invalidNumber_throwsNumberFormatException() {
    assertThatThrownBy(() -> StringUtil.stringToLongSet("abc"))
        .isInstanceOf(NumberFormatException.class);
  }

  // -------- stringToSet --------

  @Test
  void stringToSet_singleValue_returnsSetWithOneElement() {
    Set<String> result = StringUtil.stringToSet("hello");
    assertThat(result).containsExactly("hello");
  }

  @Test
  void stringToSet_twoValues_returnsSetWithBothElements() {
    Set<String> result = StringUtil.stringToSet("foo,bar");
    assertThat(result).containsExactlyInAnyOrder("foo", "bar");
  }

  @Test
  void stringToSet_trimsWhitespace() {
    Set<String> result = StringUtil.stringToSet("foo, bar");
    assertThat(result).containsExactlyInAnyOrder("foo", "bar");
  }
}
