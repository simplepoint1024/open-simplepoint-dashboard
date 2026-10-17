package org.simplepoint.core.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Typed server-side query for complete, authorized collections (for example merged catalogs).
 * Callers must enforce ownership before supplying rows; a server page is not a complete collection.
 * Shares the public field policy, AST budgets and scalar conversion with the SQL compiler.
 */
public final class CollectionQuery {
  private CollectionQuery() {}

  /** Compiles and validates once, before the caller loads data. */
  public static <T> Prepared<T> prepare(
      Class<T> type, Map<String, String> filters, Pageable pageable, Comparator<T> stableOrder) {
    var declaration = QueryCapabilities.declaration(type);
    if (declaration == null
        || pageable.getSort().stream().count() > 3
        || (pageable.isPaged()
            && (pageable.getPageSize() > declaration.maxPageSize()
                || pageable.getOffset() > Integer.MAX_VALUE))) {
      throw new InvalidQueryException("查询或分页范围超限");
    }
    QueryDocument.validateBudget(filters);
    List<Function<T, Boolean>> predicates = new ArrayList<>();
    if (filters != null) {
      filters.forEach(
          (field, value) -> {
            if (Set.of("page", "size", "sort").contains(field)) {
              return;
            }
            if (QueryDocument.PARAMETER.equals(field) || (value != null && value.startsWith("{"))) {
              var node = QueryDocument.parse(value).where();
              if (!QueryDocument.PARAMETER.equals(field)
                  && (!field.equals(node.field()) || !node.children().isEmpty())) {
                throw new InvalidQueryException("无效的列筛选");
              }
              predicates.add(compile(type, node));
            } else {
              var condition = FilterCondition.parse(value);
              var member = checkedField(type, field, condition.operator(), false);
              List<Object> values = new ArrayList<>();
              if (!condition.operator().startsWith("is:")) {
                String[] parts =
                    Set.of("in", "not:in", "between", "not:between").contains(condition.operator())
                        ? condition.value().split(",", -1)
                        : new String[] {condition.value()};
                if (parts.length > 100) {
                  throw new InvalidQueryException("筛选条件数量超限");
                }
                for (String part : parts) {
                  values.add(QueryValues.convert(member.getType(), part));
                }
              }
              predicates.add(leaf(member, condition.operator(), values));
            }
          });
    }
    Comparator<T> comparator = (left, right) -> 0;
    Set<String> sorted = new java.util.HashSet<>();
    var sort = pageable.getSort();
    if (sort.isUnsorted()) sort = DefaultTableOrder.forModel(type, name ->
        DefaultTableOrder.exists(type, name) && QueryCapabilities.readable(type, name));
    for (var order : sort) {
      if (!sorted.add(order.getProperty())) {
        throw new InvalidQueryException("排序字段不能重复");
      }
      Field field = pageable.getSort().isUnsorted() ? QueryCapabilities.field(type, order.getProperty())
          : checkedField(type, order.getProperty(), null, true);
      if (!field.trySetAccessible()) {
        throw new IllegalStateException("Query field is inaccessible");
      }
      comparator =
          comparator.thenComparing(
              (left, right) -> {
                Object a = read(field, left);
                Object b = read(field, right);
                if (a == null || b == null) {
                  return a == b ? 0 : a == null ? 1 : -1;
                }
                return order.isAscending() ? compare(a, b) : compare(b, a);
              });
    }
    return new Prepared<>(predicates, comparator.thenComparing(stableOrder), pageable);
  }

  /** An immutable query, reusable across the caller's authorized complete snapshot. */
  public record Prepared<T>(
      List<Function<T, Boolean>> predicates, Comparator<T> comparator, Pageable pageable) {
    /** Defensively copies compiled conditions. */
    public Prepared {
      predicates = List.copyOf(predicates);
    }

    /** Applies all predicates and ordering before slicing; count uses the same filtered rows. */
    public Page<T> page(List<T> source) {
      List<T> rows =
          source.stream()
              .filter(
                  row ->
                      predicates.stream()
                          .allMatch(predicate -> Boolean.TRUE.equals(predicate.apply(row))))
              .sorted(comparator)
              .toList();
      if (pageable.isUnpaged()) {
        return new PageImpl<>(rows);
      }
      int start = (int) Math.min(pageable.getOffset(), rows.size());
      int end = (int) Math.min((long) start + pageable.getPageSize(), rows.size());
      return new PageImpl<>(rows.subList(start, end), pageable, rows.size());
    }
  }

  private static <T> Function<T, Boolean> compile(Class<T> type, QueryDocument.Node node) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      List<Function<T, Boolean>> children =
          node.children().stream().map(child -> CollectionQuery.<T>compile(type, child)).toList();
      return row -> {
        if (node.op().equals("not")) {
          Boolean value = children.getFirst().apply(row);
          return value == null ? null : !value;
        }
        boolean disjunction = node.op().equals("or");
        boolean unknown = false;
        for (var child : children) {
          Boolean value = child.apply(row);
          if (value == null) {
            unknown = true;
          } else if (value == disjunction) {
            return disjunction;
          }
        }
        return unknown ? null : !disjunction;
      };
    }
    Field field = checkedField(type, node.field(), node.op(), false);
    List<Object> values = new ArrayList<>();
    if (!node.op().startsWith("is:")) {
      Iterable<JsonNode> raw = node.value().isArray() ? node.value() : List.of(node.value());
      for (var value : raw) {
        Class<?> scalar = field.getType();
        if ((scalar == String.class && !value.isTextual())
            || ((scalar == Boolean.class || scalar == boolean.class) && !value.isBoolean())) {
          throw new InvalidQueryException("筛选值与字段类型不匹配");
        }
        values.add(
            scalar == String.class
                ? value.textValue()
                : QueryValues.convert(scalar, value.asText()));
      }
    }
    return leaf(field, node.op(), values);
  }

  private static Field checkedField(Class<?> type, String name, String op, boolean sort) {
    QueryCapabilities.require(type, name, op, sort);
    Field field = QueryCapabilities.field(type, name);
    if (!field.trySetAccessible()) {
      throw new IllegalStateException("Query field is inaccessible");
    }
    return field;
  }

  private static Object read(Field field, Object row) {
    try {
      return field.get(row);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException("Query field is inaccessible", error);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int compare(Object left, Object right) {
    if (left instanceof Enum<?> enumeration) {
      return enumeration.name().compareTo(((Enum<?>) right).name());
    }
    if (left instanceof java.time.OffsetDateTime time) {
      return time.toInstant().compareTo(((java.time.OffsetDateTime) right).toInstant());
    }
    if (left instanceof java.time.ZonedDateTime time) {
      return time.toInstant().compareTo(((java.time.ZonedDateTime) right).toInstant());
    }
    return ((Comparable) left).compareTo(right);
  }

  private static <T> Function<T, Boolean> leaf(Field field, String op, List<Object> values) {
    if (op.contains("between")
        && (values.size() != 2 || compare(values.get(0), values.get(1)) > 0)) {
      throw new InvalidQueryException("区间需要两个按升序排列的值");
    }
    return row -> {
      Object value = read(field, row);
      if (op.equals("is:null")) {
        return value == null;
      }
      if (op.equals("is:not:null")) {
        return value != null;
      }
      if (value == null) {
        return null;
      }
      Object expected = values.getFirst();
      return switch (op) {
        case "equals" -> compare(value, expected) == 0;
        case "not:equals" -> compare(value, expected) != 0;
        case "in" -> values.stream().anyMatch(item -> compare(value, item) == 0);
        case "not:in" -> values.stream().noneMatch(item -> compare(value, item) == 0);
        case "like", "not:like" ->
            op.equals("like")
                == ((String) value)
                    .toLowerCase(Locale.ROOT)
                    .contains(((String) expected).trim().toLowerCase(Locale.ROOT));
        case "than:greater" -> compare(value, expected) > 0;
        case "than:less" -> compare(value, expected) < 0;
        case "than:equal:greater" -> compare(value, expected) >= 0;
        case "than:equal:less" -> compare(value, expected) <= 0;
        case "between" -> compare(value, expected) >= 0 && compare(value, values.get(1)) <= 0;
        case "not:between" -> compare(value, expected) < 0 || compare(value, values.get(1)) > 0;
        default -> throw new InvalidQueryException("不支持的筛选操作符");
      };
    };
  }
}
