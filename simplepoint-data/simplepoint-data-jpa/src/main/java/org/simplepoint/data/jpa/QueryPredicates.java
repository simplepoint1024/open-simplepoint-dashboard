package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.core.query.QueryValues;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/** Shared typed predicate compiler. Domain field and ownership policies stay with the caller. */
public final class QueryPredicates {
  private QueryPredicates() {}

  /** Builds one typed predicate without changing the caller's mandatory scope constraints. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Predicate build(
      CriteriaBuilder cb,
      Expression<?> path,
      FilterCondition condition,
      EscapeCharacter escape,
      int maxValues) {
    String op = condition.operator();
    if (op.equals("is:null")) {
      return cb.isNull(path);
    }
    if (op.equals("is:not:null")) {
      return cb.isNotNull(path);
    }
    path = QueryExpressions.scalar(cb, path);
    String text = condition.value();
    if (text == null || text.isBlank() || text.length() > FilterCondition.MAX_VALUE_LENGTH) {
      throw new InvalidQueryException("筛选值不能为空或超过长度限制");
    }
    final Class<?> type = QueryExpressions.javaType(path);
    if (op.equals("like") || op.equals("not:like")) {
      if (type != String.class) {
        throw new InvalidQueryException("包含筛选仅支持文本字段");
      }
      char ch = escape.getEscapeCharacter();
      String pattern =
          text.trim()
              .toLowerCase(Locale.ROOT)
              .replace(String.valueOf(ch), "" + ch + ch)
              .replace("%", ch + "%")
              .replace("_", ch + "_");
      Predicate result = cb.like(cb.lower((Expression<String>) path), "%" + pattern + "%", ch);
      return op.equals("like") ? result : cb.not(result);
    }
    boolean multiple = Set.of("in", "not:in", "between", "not:between").contains(op);
    String[] parts = multiple ? text.split(",", -1) : new String[] {text};
    if (parts.length > maxValues) {
      throw new InvalidQueryException("单列筛选值数量超限");
    }
    List<Object> values = new ArrayList<>();
    for (String part : parts) {
      if (part.isBlank()) {
        throw new InvalidQueryException("筛选值不能为空");
      }
      values.add(QueryValues.convert(type, part));
    }
    Object value = values.getFirst();
    return compare(cb, path, op, values);
  }

  /** Structured values preserve commas and JSON types instead of being flattened to CSV. */
  public static Predicate build(CriteriaBuilder cb, Expression<?> path, QueryDocument.Node node) {
    String op = node.op();
    if (op.equals("is:null")) {
      return cb.isNull(path);
    }
    if (op.equals("is:not:null")) {
      return cb.isNotNull(path);
    }
    path = QueryExpressions.scalar(cb, path);
    if (op.contains("like")) {
      if (!node.value().isTextual()) {
        throw new InvalidQueryException("文本筛选需要字符串");
      }
      return build(
          cb,
          path,
          new FilterCondition(op, node.value().textValue()),
          EscapeCharacter.DEFAULT,
          100);
    }
    List<Object> values = new ArrayList<>();
    Iterable<com.fasterxml.jackson.databind.JsonNode> raw =
        node.value().isArray() ? node.value() : List.of(node.value());
    for (var value : raw) {
      Class<?> type = QueryExpressions.javaType(path);
      if (type == String.class && !value.isTextual()) {
        throw new InvalidQueryException("文本字段需要字符串");
      }
      if ((type == Boolean.class || type == boolean.class) && !value.isBoolean()) {
        throw new InvalidQueryException("布尔字段需要布尔值");
      }
      values.add(
          type == String.class ? value.textValue() : QueryValues.convert(type, value.asText()));
    }
    return compare(cb, path, op, values);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Predicate compare(
      CriteriaBuilder cb, Expression<?> path, String op, List<Object> values) {
    Object value = values.getFirst();
    if (op.equals("equals")) {
      return cb.equal(path, value);
    }
    if (op.equals("not:equals")) {
      return cb.notEqual(path, value);
    }
    if (op.equals("in")) {
      return path.in(values);
    }
    if (op.equals("not:in")) {
      return cb.not(path.in(values));
    }
    Class<?> type = QueryExpressions.javaType(path);
    if (!(value instanceof Comparable comparable)
        || value instanceof Boolean
        || type.isEnum()
        || type == UUID.class) {
      throw new InvalidQueryException("此字段不支持大小或区间比较");
    }
    Expression expression = path;
    return switch (op) {
      case "than:greater" -> cb.greaterThan(expression, comparable);
      case "than:less" -> cb.lessThan(expression, comparable);
      case "than:equal:greater" -> cb.greaterThanOrEqualTo(expression, comparable);
      case "than:equal:less" -> cb.lessThanOrEqualTo(expression, comparable);
      case "between", "not:between" -> {
        if (values.size() != 2 || comparable.compareTo(values.get(1)) > 0) {
          throw new InvalidQueryException("区间需要两个按升序排列的值");
        }
        Predicate range = cb.between(expression, comparable, (Comparable) values.get(1));
        yield op.equals("between") ? range : cb.not(range);
      }
      default -> throw new InvalidQueryException("不支持的筛选操作符");
    };
  }
}
