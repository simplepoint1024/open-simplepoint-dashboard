package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class CollectionQueryTest {
  @TableQuery({@QueryField("text"), @QueryField("amount"), @QueryField("enabled")})
  record Row(int id, String text, BigDecimal amount, Boolean enabled) {}

  private final List<Row> rows =
      List.of(
          new Row(1, "Alpha a,b:%_", new BigDecimal("2"), true),
          new Row(2, "Beta", new BigDecimal("10"), false),
          new Row(3, null, null, null));

  private List<Integer> ids(String field, String condition) {
    return CollectionQuery.prepare(
            Row.class,
            Map.of(field, condition),
            Pageable.unpaged(),
            Comparator.comparingInt(Row::id))
        .page(rows)
        .map(Row::id)
        .getContent();
  }

  @Test
  void everyScalarOperatorUsesTypedValuesAndNullSemantics() {
    assertThat(ids("amount", "equals:2")).containsExactly(1);
    assertThat(ids("amount", "not:equals:2")).containsExactly(2);
    assertThat(ids("amount", "in:2,10")).containsExactly(1, 2);
    assertThat(ids("amount", "not:in:2")).containsExactly(2);
    assertThat(ids("amount", "between:2,10")).containsExactly(1, 2);
    assertThat(ids("amount", "not:between:2,2")).containsExactly(2);
    assertThat(ids("amount", "than:greater:2")).containsExactly(2);
    assertThat(ids("amount", "than:less:10")).containsExactly(1);
    assertThat(ids("amount", "than:equal:greater:10")).containsExactly(2);
    assertThat(ids("amount", "than:equal:less:2")).containsExactly(1);
    assertThat(ids("amount", "is:null")).containsExactly(3);
    assertThat(ids("amount", "is:not:null")).containsExactly(1, 2);
    assertThat(ids("text", "like:A,B:%_")).containsExactly(1);
    assertThat(ids("text", "not:like:Alpha")).containsExactly(2);
    assertThat(ids("enabled", "equals:false")).containsExactly(2);
  }

  @Test
  void nestedGroupsPreserveSqlUnknownAndComposeWithHeaderFilters() {
    String document =
        """
        {"version":1,"where":{"op":"not","children":[{"op":"or","children":[
          {"field":"text","op":"like","value":"Alpha"},
          {"field":"enabled","op":"equals","value":true}]}]}}
        """;
    assertThat(ids("$query", document)).containsExactly(2);
    var query =
        CollectionQuery.prepare(
            Row.class,
            Map.of("$query", document, "amount", "than:greater:10"),
            Pageable.unpaged(),
            Comparator.comparingInt(Row::id));
    assertThat(query.page(rows).getTotalElements()).isZero();
  }

  @Test
  void sortAndCountRunBeforePagingWithNullsLastAndStableTies() {
    for (var direction : Sort.Direction.values()) {
      var query =
          CollectionQuery.prepare(
              Row.class,
              Map.of(),
              PageRequest.of(1, 1, Sort.by(direction, "amount")),
              Comparator.comparingInt(Row::id));
      assertThat(query.page(rows).getTotalElements()).isEqualTo(3);
      assertThat(query.page(rows).map(Row::id).getContent())
          .containsExactly(direction.isAscending() ? 2 : 1);
      assertThat(
              CollectionQuery.prepare(
                      Row.class,
                      Map.of(),
                      PageRequest.of(2, 1, Sort.by(direction, "amount")),
                      Comparator.comparingInt(Row::id))
                  .page(rows)
                  .map(Row::id)
                  .getContent())
          .containsExactly(3);
    }
    assertThat(rows).extracting(Row::id).containsExactly(1, 2, 3);
  }

  @Test
  void decimalsRemainExactAndEmptyTextIsNotNull() {
    var values =
        List.of(
            new Row(1, "", new BigDecimal("9007199254740993.01"), true),
            new Row(2, null, new BigDecimal("9007199254740993.02"), true));
    assertThat(
            CollectionQuery.prepare(
                    Row.class,
                    Map.of("amount", "equals:9007199254740993.02"),
                    Pageable.unpaged(),
                    Comparator.comparingInt(Row::id))
                .page(values)
                .map(Row::id)
                .getContent())
        .containsExactly(2);
    assertThat(
            CollectionQuery.prepare(
                    Row.class,
                    Map.of(
                        "text",
                        """
                        {"version":1,"where":{"field":"text","op":"equals","value":""}}
                        """
                            .trim()),
                    Pageable.unpaged(),
                    Comparator.comparingInt(Row::id))
                .page(values)
                .map(Row::id)
                .getContent())
        .containsExactly(1);
  }

  @Test
  void invalidFieldsTypesRangesAndPageLimitsFailBeforeLoadingRows() {
    for (var filters :
        List.of(
            Map.of("secret", "equals:x"),
            Map.of("amount", "between:10,2"),
            Map.of("enabled", "equals:yes"),
            Map.of(
                "text",
                """
                {"version":1,"where":{"field":"amount","op":"equals","value":2}}
                """
                    .trim()))) {
      assertThatThrownBy(
              () ->
                  CollectionQuery.prepare(
                      Row.class, filters, PageRequest.of(0, 10), Comparator.comparingInt(Row::id)))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(
            () ->
                CollectionQuery.prepare(
                    Row.class, Map.of(), PageRequest.of(0, 201), Comparator.comparingInt(Row::id)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(
            () ->
                CollectionQuery.prepare(
                    Row.class,
                    Map.of(),
                    PageRequest.of(0, 10, Sort.by("secret")),
                    Comparator.comparingInt(Row::id)))
        .isInstanceOf(InvalidQueryException.class);
  }
}
