package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.lambda.Property;

class ConditionsBuilderTest {

  /** Simple sample entity with fields and standard getters. */
  static class Sample {
    private String name;
    private String status;
    private boolean active;

    Sample(String name, String status, boolean active) {
      this.name = name;
      this.status = status;
      this.active = active;
    }

    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isActive() { return active; }
  }

  // -------- factory methods --------

  @Test
  void of_withExample_buildsEmpty() {
    ConditionsBuilder<Sample> builder = ConditionsBuilder.of(new Sample("a", "ok", true));
    assertThat(builder.build()).isEmpty();
  }

  @Test
  void of_withoutExample_buildsEmpty() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.build()).isEmpty();
  }

  // -------- like --------

  @Test
  void like_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("Alice", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).like(Sample::getName).build();
    assertThat(cond).containsEntry("name", "like:Alice");
  }

  @Test
  void like_getterWithExplicitValue_storesProvidedValue() {
    Sample sample = new Sample("Alice", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .like(Sample::getName, "Bob").build();
    assertThat(cond).containsEntry("name", "like:Bob");
  }

  // -------- notLike --------

  @Test
  void notLike_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("Alice", "disabled", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).notLike(Sample::getName).build();
    assertThat(cond).containsEntry("name", "not:like:Alice");
  }

  @Test
  void notLike_getterWithExplicitValue_storesProvidedValue() {
    Sample sample = new Sample("X", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .notLike(Sample::getStatus, "disabled").build();
    assertThat(cond).containsEntry("status", "not:like:disabled");
  }

  // -------- in --------

  @Test
  void in_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("X", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).in(Sample::getStatus).build();
    assertThat(cond).containsEntry("status", "in:active");
  }

  @Test
  void in_getterWithVarargs_joinsWithComma() {
    Sample sample = new Sample("X", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .in(Sample::getStatus, "active", "pending").build();
    assertThat(cond).containsEntry("status", "in:active,pending");
  }

  @Test
  void in_getterWithCollection_joinsWithComma() {
    Sample sample = new Sample("X", "active", true);
    List<String> values = Arrays.asList("active", "inactive");
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .in(Sample::getStatus, values).build();
    assertThat(cond).containsEntry("status", "in:active,inactive");
  }

  // -------- notIn --------

  @Test
  void notIn_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("X", "banned", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).notIn(Sample::getStatus).build();
    assertThat(cond).containsEntry("status", "not:in:banned");
  }

  @Test
  void notIn_getterWithVarargs_joinsWithComma() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .notIn(Sample::getStatus, "banned", "suspended").build();
    assertThat(cond).containsEntry("status", "not:in:banned,suspended");
  }

  // -------- equals --------

  @Test
  void equals_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("Alice", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).equals(Sample::getName).build();
    assertThat(cond).containsEntry("name", "equals:Alice");
  }

  @Test
  void equals_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("Alice", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .equals(Sample::getName, "Bob").build();
    assertThat(cond).containsEntry("name", "equals:Bob");
  }

  // -------- notEquals --------

  @Test
  void notEquals_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("Alice", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).notEquals(Sample::getName).build();
    assertThat(cond).containsEntry("name", "not:equals:Alice");
  }

  @Test
  void notEquals_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("Alice", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .notEquals(Sample::getName, "Charlie").build();
    assertThat(cond).containsEntry("name", "not:equals:Charlie");
  }

  // -------- isNull / isNotNull --------

  @Test
  void isNull_storesIsNullCondition() {
    Sample sample = new Sample(null, "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).isNull(Sample::getName).build();
    assertThat(cond).containsEntry("name", "is:null:null");
  }

  @Test
  void isNotNull_storesIsNotNullCondition() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).isNotNull(Sample::getName).build();
    assertThat(cond).containsEntry("name", "is:not:null:X");
  }

  // -------- greaterThan --------

  @Test
  void greaterThan_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("2024", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).greaterThan(Sample::getName).build();
    assertThat(cond).containsEntry("name", "than:greater:2024");
  }

  @Test
  void greaterThan_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .greaterThan(Sample::getName, "100").build();
    assertThat(cond).containsEntry("name", "than:greater:100");
  }

  // -------- lessThan --------

  @Test
  void lessThan_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("500", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).lessThan(Sample::getName).build();
    assertThat(cond).containsEntry("name", "than:less:500");
  }

  @Test
  void lessThan_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .lessThan(Sample::getName, "999").build();
    assertThat(cond).containsEntry("name", "than:less:999");
  }

  // -------- greaterThanOrEqual --------

  @Test
  void greaterThanOrEqual_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("10", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .greaterThanOrEqual(Sample::getName).build();
    assertThat(cond).containsEntry("name", "than:equal:greater:10");
  }

  @Test
  void greaterThanOrEqual_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .greaterThanOrEqual(Sample::getName, "50").build();
    assertThat(cond).containsEntry("name", "than:equal:greater:50");
  }

  // -------- lessThanOrEqual --------

  @Test
  void lessThanOrEqual_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("99", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .lessThanOrEqual(Sample::getName).build();
    assertThat(cond).containsEntry("name", "than:equal:less:99");
  }

  @Test
  void lessThanOrEqual_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .lessThanOrEqual(Sample::getName, "200").build();
    assertThat(cond).containsEntry("name", "than:equal:less:200");
  }

  // -------- between --------

  @Test
  void between_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("5", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).between(Sample::getName).build();
    assertThat(cond).containsEntry("name", "between:5");
  }

  @Test
  void between_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .between(Sample::getName, "1,10").build();
    assertThat(cond).containsEntry("name", "between:1,10");
  }

  // -------- notBetween --------

  @Test
  void notBetween_getter_storesFieldValueFromExample() {
    Sample sample = new Sample("5", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample).notBetween(Sample::getName).build();
    assertThat(cond).containsEntry("name", "not:between:5");
  }

  @Test
  void notBetween_getterWithValue_storesProvidedValue() {
    Sample sample = new Sample("X", "ok", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .notBetween(Sample::getName, "1,10").build();
    assertThat(cond).containsEntry("name", "not:between:1,10");
  }

  // -------- chaining --------

  @Test
  void chaining_multipleConditions_allPresent() {
    Sample sample = new Sample("Alice", "active", true);
    Map<String, String> cond = ConditionsBuilder.of(sample)
        .like(Sample::getName)
        .equals(Sample::getStatus, "active")
        .build();
    assertThat(cond).hasSize(2)
        .containsEntry("name", "like:Alice")
        .containsEntry("status", "equals:active");
  }

  // -------- boolean is-getter --------

  @Test
  void resolveFieldName_isGetter_extractsFieldName() {
    Sample sample = new Sample("X", "ok", true);
    ConditionsBuilder<Sample> builder = ConditionsBuilder.of(sample);
    // isActive -> "active"
    String name = builder.resolveFieldName((Property<Sample, Boolean>) Sample::isActive);
    assertThat(name).isEqualTo("active");
  }

  @Test
  void resolveFieldValue_isGetter_extractsFieldValue() {
    Sample sample = new Sample("X", "ok", true);
    ConditionsBuilder<Sample> builder = ConditionsBuilder.of(sample);
    String val = builder.resolveFieldValue((Property<Sample, Boolean>) Sample::isActive);
    assertThat(val).isEqualTo("true");
  }

  // -------- decapitalize --------

  @Test
  void decapitalize_regularString_lowercasesFirstChar() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.decapitalize("Name")).isEqualTo("name");
    assertThat(builder.decapitalize("Status")).isEqualTo("status");
  }

  @Test
  void decapitalize_alreadyLowercase_unchanged() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.decapitalize("name")).isEqualTo("name");
  }

  @Test
  void decapitalize_singleChar_lowercases() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.decapitalize("A")).isEqualTo("a");
  }

  @Test
  void decapitalize_null_returnsNull() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.decapitalize(null)).isNull();
  }

  @Test
  void decapitalize_emptyString_returnsEmpty() {
    ConditionsBuilder<Object> builder = ConditionsBuilder.of();
    assertThat(builder.decapitalize("")).isEmpty();
  }

  // -------- constants --------

  @Test
  void constants_haveExpectedValues() {
    assertThat(ConditionsBuilder.PREFIX_LIKE).isEqualTo("like");
    assertThat(ConditionsBuilder.PREFIX_IN).isEqualTo("in");
    assertThat(ConditionsBuilder.PREFIX_NOT_IN).isEqualTo("not:in");
    assertThat(ConditionsBuilder.PREFIX_NOT_LIKE).isEqualTo("not:like");
    assertThat(ConditionsBuilder.PREFIX_BETWEEN).isEqualTo("between");
    assertThat(ConditionsBuilder.PREFIX_NOT_BETWEEN).isEqualTo("not:between");
    assertThat(ConditionsBuilder.PREFIX_EQUALS).isEqualTo("equals");
    assertThat(ConditionsBuilder.PREFIX_NOT_EQUALS).isEqualTo("not:equals");
    assertThat(ConditionsBuilder.PREFIX_THAN_GREATER).isEqualTo("than:greater");
    assertThat(ConditionsBuilder.PREFIX_THAN_LESS).isEqualTo("than:less");
    assertThat(ConditionsBuilder.PREFIX_THAN_EQUAL_GREATER).isEqualTo("than:equal:greater");
    assertThat(ConditionsBuilder.PREFIX_THAN_EQUAL_LESS).isEqualTo("than:equal:less");
    assertThat(ConditionsBuilder.PREFIX_IS_NULL).isEqualTo("is:null");
    assertThat(ConditionsBuilder.PREFIX_IS_NOT_NULL).isEqualTo("is:not:null");
  }
}
